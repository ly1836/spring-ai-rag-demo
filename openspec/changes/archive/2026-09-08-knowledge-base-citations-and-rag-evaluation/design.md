## Context

当前项目以 `DocumentLoaderService` 直接把文档写入 PgVector，并以 `ent_code + source` 作为覆盖边界；问答链能够召回文档，但回答 DTO、SSE 协议和历史消息均不携带可验证的证据身份。文档也没有 MySQL 注册记录，因此无法表达知识库、稳定文档 ID、版本、处理中/失败状态或上一可用版本。与此同时，RAG 修改主要依靠人工体验判断，缺少版本化数据集和可重复指标。

本变更跨越 `knowledge`、`chat/rag`、`controller`、`conversation`、`dao`、`init`、静态前端和 Maven 测试生命周期。主要约束如下：

- 文档二进制与向量写入属于长耗时、跨 PgVector 的操作，不能包在 MySQL 本地事务中，也不存在可用的分布式事务。
- 所有知识库、文档版本、向量、引用和评测临时数据必须以 `ent_code` 隔离；跨租户对象统一表现为不存在。
- 生产问答继续通过 `ModelRegistry` 路由模型，并由现有 `AssistantLifecycleService` 执行 `BillingService.checkQuota()` 与 `deductTokens()`；评测调用真实模型但不能创建用户会话或扣减租户余额。
- 普通 `mvn test` 必须离线且无需数据库、模型 Key；只有显式 `rag-eval` Profile 才连接真实 PgVector/LLM。
- 4.0.0 接受一次明确的数据迁移边界：旧向量缺少稳定证据元数据，不自动纳入受管知识库检索，需重新导入。

主要使用者包括维护租户知识的业务用户、查看引用的问答用户，以及用评测报告判断 RAG 回归的开发者。

## Goals / Non-Goals

**Goals:**

- 建立租户级知识库和稳定文档身份，支持状态、版本、替换、删除及上一可用版本保护。
- 让非流式、流式和历史回放共享同一份经验证引用，且引用只来自本轮生产检索结果。
- 收口生产 RAG 链路，使在线问答和离线评测复用同一检索、过滤、上下文编号和引用校验实现。
- 提供不少于 50 条的版本化评测集、可机读报告和关键隔离/引用硬门禁。
- 使用适合中文技术资料的本地多语言嵌入模型，并避免单个超大文档在存在其他合格来源时独占最终证据。
- 保持现有单模块、MyBatis-Plus/MySQL、PgVector、本地嵌入模型与零构建前端架构。

**Non-Goals:**

- 不实现 OCR、对象存储、在线编辑、历史版本回滚、外部全文搜索引擎、Query Rewrite、LLM 重排或在线评测后台。
- 不新增认证授权模型；继续复用 `TenantFilter`、`TenantContext` 和当前用户上下文。
- 不迁移或猜测旧向量的文档身份，不为旧历史消息补造引用。
- 不以 LLM Judge 作为首版默认构建硬门禁。

## Decisions

### 1. 以 `knowledge` 业务域管理知识库和文档注册

新增 `com.example.rag.knowledge` 包，核心职责由 `KnowledgeBaseService`、`KnowledgeDocumentService` 和 `KnowledgeDocumentIngestionService` 承担；新增 `KnowledgeBaseController` 只做参数绑定并返回 `RespVO<T>`。业务域 VO 嵌套在 `KnowledgeVO`，持久化对象和 Mapper 分别放入既有 `dao/entity` 与 `dao/mapper`。

MySQL 新增两张表：

| 表 | 关键字段 | 索引与隔离 |
| --- | --- | --- |
| `a_knowledge_base` | `id`、`knowledge_base_id`、`ent_code`、`name`、`description`、`status(active/inactive/deleted)`、`is_default`、`created_by`、`create_time`、`update_time` | `knowledge_base_id` 使用 UUID；唯一键 `(ent_code, knowledge_base_id)`、普通索引 `(ent_code, status)`；所有读写条件显式包含 `ent_code` |
| `a_knowledge_document` | `id`、`document_id`、`knowledge_base_id`、`ent_code`、`source_name`、`content_type`、`size_bytes`、`checksum_sha256`、`embedding_model`、`version`、`status(processing/ready/failed/superseded/deleted)`、`chunk_count`、`error_message`、`created_by`、`create_time`、`update_time` | 每一行表示稳定文档的一个版本；唯一键 `(ent_code, document_id, version)`，查询索引 `(ent_code, knowledge_base_id, status)` 和 `(ent_code, knowledge_base_id, source_name)` |

知识管理服务通过绑定 ERP MySQL 数据源的 MyBatis-Plus Mapper 操作表，Wrapper 和自定义 SQL 均显式携带 `ent_code`，禁止直接使用主 PgVector 数据源或拼接 SQL。知识库创建、更新、删除和默认库切换在事务中统一先锁定当前 `a_tenant` 行；更新与删除再读取 `FOR UPDATE` 返回的最新知识库实体，避免使用锁前状态。该锁顺序同时串行化同租户名称校验和默认标记更新，保证并发下名称唯一且最多一个默认库；租户首次访问知识能力时懒创建名为“默认知识库”的默认库。

选择该方案而不是把管理元数据放进 PgVector JSON，是因为列表、状态、唯一性和事务切换属于关系数据职责；选择“每版本一行”而不是覆盖单行，是为了在新版本失败时仍能保留上一 `ready` 版本。

### 2. 文档导入采用三阶段 Saga 与版本资格过滤

导入流程不使用跨库事务：

1. `KnowledgeDocumentService` 在短 MySQL 事务中校验租户/知识库、解析同名文档的稳定 `documentId`，创建下一版本 `processing` 记录并提交。
2. `KnowledgeDocumentIngestionService` 在事务外执行 Tika 提取、字符/分片/token 边界校验，按每批最多 100 个分片写入 PgVector。每个分片写入 `ent_code`、`knowledge_base_id`、`document_id`、`document_version`、`chunk_id`、`chunk_index`、`source`。
3. 全部写入成功后，在短 MySQL 事务中锁定该文档全部版本：若不存在更高版本的 `ready` 记录，则把更低的上一 `ready` 版本改为 `superseded`、把当前版本改为 `ready`；若更高版本已经先完成，则把迟到的当前版本直接标记为 `superseded` 并清理自身向量，禁止旧版本重新覆盖新版本。失败时按 `ent_code + knowledge_base_id + document_id + document_version` 清理新向量，并把版本标记为 `failed`、仅保存固定安全错误摘要。

检索先按 `ent_code + knowledge_base_id` 从向量库做有界过采样，再由 `RagDocumentEligibilityFilter` 批量查询 MySQL，仅保留状态为 `ready` 的当前版本并截断到 topK。这样 `processing`、`failed`、`superseded` 和 `deleted` 分片即使处于补偿窗口也不会成为证据。默认过采样为 `topK * 3`，上限 24；请求 `topK` 超过 24 时在访问知识库和向量库前返回参数错误，禁止静默少返回。成功后的旧向量清理失败由日志暴露并允许后续维护任务重试。

显式替换接口按 `documentId` 创建新版本，并在租户锁内确认新 `source_name` 未被同一知识库中的其他未删除稳定文档占用；兼容 `/api/upload` 和 `/api/load` 在默认知识库中以 `source_name` 查找稳定文档，同名则创建新版本，不同名则生成新 `documentId`。删除把该文档全部版本软删后再尽力删除向量。默认知识库和非空知识库不能直接删除，避免隐式级联数据损失。

选择 Saga 与资格过滤而不是“先删旧向量再写新向量”，是为了消除失败时知识空窗；不引入消息队列，是因为当前单体规模下同步补偿与可见状态足够，队列会扩大运维面。

### 3. API 采用知识库子资源，兼容入口只代理默认知识库

新增接口如下，全部通过当前 `ent_code` 和用户上下文校验：

| 方法与路径 | 请求/响应 | 主要错误语义 |
| --- | --- | --- |
| `GET /api/knowledge-bases` | 返回知识库 ID、名称、描述、状态、默认标记、文档统计 | 数据库异常为 `SYSTEM_ERROR` |
| `POST /api/knowledge-bases` | JSON `{name, description}`，返回新知识库 | 空名称/超长为 `PARAM_ERROR`；重名为 `BIZ_ERROR` |
| `PUT /api/knowledge-bases/{knowledgeBaseId}` | JSON 可修改名称、描述、启停或设为默认 | 非法字段为 `PARAM_ERROR`；不存在/跨租户/停用默认库为 `BIZ_ERROR` |
| `DELETE /api/knowledge-bases/{knowledgeBaseId}` | 成功返回空结果 | 默认库、非空库、不存在/跨租户为 `BIZ_ERROR` |
| `GET /api/knowledge-bases/{knowledgeBaseId}/documents` | 返回稳定文档 ID、来源、当前版本/状态、分片数、错误摘要和时间 | 不存在/跨租户为 `BIZ_ERROR` |
| `POST /api/knowledge-bases/{knowledgeBaseId}/documents` | multipart `file`，返回文档版本结果 | 空文件、格式/大小/资源越界为 `PARAM_ERROR`；停用库为 `BIZ_ERROR`；基础设施失败为 `SYSTEM_ERROR` 且版本可查为 `failed` |
| `PUT /api/knowledge-bases/{knowledgeBaseId}/documents/{documentId}/content` | multipart `file`，创建下一版本 | 对象不存在/跨租户为 `BIZ_ERROR`；校验和导入错误同上 |
| `DELETE /api/knowledge-bases/{knowledgeBaseId}/documents/{documentId}` | 软删文档并清理向量 | 不存在/跨租户为 `BIZ_ERROR` |

`KnowledgeBaseController`、`ChatController` 只绑定参数和调用 Service；异常继续由 `GlobalExceptionHandler` 映射。`/api/ask`、`/api/ask/stream` 的请求增加可选 `knowledgeBaseId`，`/api/search` 增加同名查询参数；缺省时由 Service 解析当前租户默认库。`/api/load`、`/api/upload` 保留路径和请求方式，但只代理默认知识库导入。4.0.0 不读取旧向量，升级说明要求重新导入。

### 4. 生产问答与评测复用 `RagAnswerService`

在 `chat/rag` 下新增 `RagAdvisorFactory`、`RagDocumentEligibilityFilter`、`RagContextFormatter`、`RagEvidenceExtractor`、`RagCitationValidator` 和 `RagAnswerService`。`RagAdvisorFactory` 使用 Spring AI 模块化 RAG 组件组装检索链：租户/知识库过滤 → 有界过采样 → 版本资格过滤 → topK 截断 → 编号上下文。上下文中的每个证据块分配稳定序号，并要求模型只以 `[n]` 标注提供的证据。

`RagAnswerService` 返回回答文本、实际合格召回分片和经校验引用，不操作会话和余额。生产 `ErpAssistantService`/`AssistantLifecycleService` 调用它，并继续执行 `ModelRegistry.getChatModel(modelId)`、问答前 `BillingService.checkQuota()`、问答后一次 `deductTokens()`。knowledge 模式不注册 ERP Tool；auto 模式保留既有 Tool Calling 与图表能力，但 RAG 部分必须接入同一受管知识库、版本资格和引用链，data 模式不访问向量库。异常和取消按本轮已观测 usage 沿用最多一次结算规则。评测 Runner 也调用 `RagAnswerService`，但使用随机隔离租户/知识库，不走会话生命周期和余额扣减，并单独记录真实 usage。

`RagAdvisorFactory` 每次只构造请求级 Advisor，异步检索统一使用 Spring 管理的共享 `ragTaskExecutor`，由容器负责线程池启动和关闭，并通过上下文装饰器传播租户信息，禁止请求级 Advisor 隐式创建独立线程池。请求级检索器固定使用进入问答或搜索入口时的用户原始问题；auto 模式为 Tool Calling 增加的当前轮守卫提示和重试提示只发送给模型，不得成为向量查询文本。auto 模式继续使用兼容参数 `topK = 5`、相似度阈值 `0.5`；knowledge 模式使用 `topK = 8`、相似度阈值 `0.25`，两组参数集中在生产默认配置中供真实评测复用。

本地嵌入模型从偏英文的 `all-MiniLM-L6-v2` 切换为 `paraphrase-multilingual-MiniLM-L12-v2`。新模型继续输出 384 维向量、采用与 Spring AI `TransformersEmbeddingModel` 一致的 attention-mask 平均池化，并保持 128 token 的现有分片边界。每个新分片写入固定 `embedding_model` 元数据；受管检索同时过滤该模型身份，因此旧模型生成的 384 维向量也不能与新向量混用。模型文件固定到已核验的上游修订并随应用 classpath 发布；模型与分词器均通过 Git LFS 管理，发布构建前必须将两个资源完整下载到本地，并通过本地资源测试分别校验固定 SHA-256。部署后所有仍需使用的文档必须重新导入。

文档版本只在向量写入成功并晋级时记录当前 `embedding_model`。已有 `ready` 记录升级后该字段保持为空，不根据 384 维或来源名猜测模型；文档列表通过 `requiresReindex` 标明当前可用版本是否需要重新导入，前端把这类记录显示为“需重新导入”并继续提供替换入口。数据库状态仍保留原始导入结果，不新增伪造的生命周期状态。

检索入口完整保留用户原问题，不采纳 Tool 守卫提示或 LLM 改写。对于 `Rabbit MQ` 这类由产品主体加常见缩写后缀、且被空格拆开的写法，只在原问题末尾追加确定性的紧凑别名（如 `rabbitmq`），兼容自然输入差异；不扩展业务同义词，也不改变回答问题。

`RagAdvisorFactory` 在构建向量过滤条件前读取当前知识库的 `ready` 文档身份，并对用户原始问题做轻量标题匹配：忽略扩展名、大小写、空白和标点后，如果问题明确包含唯一文档标题，则在既有租户、知识库和模型过滤上追加该 `source`，让“介绍下渠道技术方案”只从对应文档取证；标题重名或没有明确命中时仍执行普通语义检索。`RagDocumentEligibilityFilter` 对普通候选先完成现有版本资格校验，再优先选取不同 `document_id` 的首条候选，最后按原相似度顺序补齐 topK，使较小文档至少有机会进入上下文，同时不在只有一个合格来源时人为减少召回数。标题匹配、来源分散和引用继续使用同一次生产检索结果，不为引用增加第二次向量查询。

引用证据直接从 Spring AI 本轮响应元数据中的检索文档提取；若不同 Advisor 产生不同元数据键，由 `RagEvidenceExtractor` 统一适配。生产引用校验与真实评测共享 Markdown 引用提取规则，转义编号、行内代码和围栏代码中的 `[n]` 只视为字面量。系统不得为引用再次调用 `VectorStore.similaritySearch()`。这一选择避免答案上下文与引用来自两次非确定性检索。

### 5. 引用是有界、不可变的回答快照

`ChatVO.CitationResponse` 包含 `citationId`（本轮 `[n]` 序号）、`knowledgeBaseId`、`documentId`、`documentVersion`、`chunkId`、`chunkIndex`、`source`、`excerpt` 和 `score`。`RagCitationValidator` 只接受答案中实际出现且能映射到本轮合格召回文档的 `[n]`；排序按首次出现顺序、去重，最多返回 8 条，单条 excerpt 最多 500 字符，持久化 JSON 总长度最多 32 KiB。模型无合法标记时返回空引用，不猜测来源。

非流式 `AskResponse` 增加 `knowledgeBaseId`、`citations`，并把 `ragDocCount` 设为资格过滤后的召回分片数。SSE 成功顺序固定为 `delta* → citations? → chart? → done`；`citations` 的 JSON `data` 一次性携带完整数组，`done` 对所有成功 RAG 回答都携带实际 `knowledgeBaseId`，使空引用回答也能确认本轮知识库。异常或取消不得发送引用、图表和成功 `done`，已生成但尚未完成校验的引用不得展示。

MySQL `a_chat_message` 增加可空 `knowledge_base_id varchar(36)` 与 `rag_citations json`。`AssistantLifecycleService` 在成功助手消息上原子保存文本、`rag_doc_count`、知识库 ID、引用 JSON 和图表；用户消息及失败/取消助手消息引用为空。历史接口直接反序列化该快照，不访问 PgVector，也不随文档替换/删除改写。无效或超限的历史 JSON 按空引用降级并记录告警，不能使整个会话查询失败。

### 6. 评测集、指标与 Maven 生命周期分离

在 `src/test/resources/rag-eval/v1/` 维护 fixture 文档、`cases.jsonl` 与 `baseline.json`。首批至少 50 条：20 条单文档、10 条多文档、10 条同义改写、5 条无答案、5 条租户/知识库隔离；每条记录有稳定 `caseId`、问题、目标知识库、期望文档 ID、可回答标志、答案关键事实和标签。

`src/test/java/.../rageval` 中的 `RagEvaluationIT` 只在 `rag-eval` Profile 下由 Maven Failsafe 执行。Runner 先创建随机测试租户和知识库、通过生产导入组件写入 fixture，再通过生产 `RagAnswerService` 逐例执行，最后清理 MySQL 与 PgVector 数据。缺少 PgVector/MySQL、模型配置或 Key 时明确失败。报告同时输出 JSON 与 Markdown 到 `target/rag-eval/`，包含：

- `Recall@5`：前 5 条召回中命中的期望文档数除以期望文档数，再对可回答用例求平均。
- `MRR@5`：首个期望文档排名倒数，对可回答用例求平均。
- 空召回率、无答案拒答率、引用有效率、关键事实命中率、P50/P95 延迟、输入/输出 Token 与按 Provider 价目配置估算的成本。
- Spring AI `RelevancyEvaluator` 和 `FactCheckingEvaluator` 的相关性/事实一致性，仅报告，不作为首版默认门禁。

每条 fixture 可选配置禁止出现在答案中的关键错误结论短语。Runner 对答案和关键事实统一移除大小写、空白、标点及 Markdown 符号后进行确定性比较，逐例报告关键事实命中数；禁止短语以肯定语义出现时属于安全语义硬失败，紧邻“并非”“不能”“不可”等明确否定词时不计为命中，但回答后续再次肯定同一错误结论时仍须失败。该门禁与仍然仅报告的 LLM Judge 分数相互独立。

真实评测调用 `RagAnswerService` 时复用 production knowledge 的 topK 和相似度阈值。回答模型与两个 LLM Judge 的 usage 分别采集，报告逐例展示两部分 Token、估算成本和总成本，聚合 Token 与成本必须包含 Judge 调用，避免低估评测预算。

单条用例执行失败时 Runner 保持快速失败，避免继续产生无效模型费用；异常结束前仍写出部分报告，并将已完成数/总数和具体失败原因加入硬门禁，禁止把部分指标误认为完整评测结果。最终清理按文档向量和随机租户关系表分别执行，单步失败不阻止后续步骤；最后仍无法确认完成的清理项写入 JSON/Markdown 报告并加入硬门禁。若评测本身已经失败，清理和报告异常只作为补充信息，不能覆盖原始失败原因。

硬门禁为：跨租户或跨知识库召回数必须为 0、无效引用数必须为 0、引用必须全部属于本轮召回、fixture 配置的关键错误结论必须为 0；版本化基线存在时 Recall@5 不得下降超过 5 个百分点。目标值为 Recall@5 ≥ 85%、无答案拒答率 ≥ 90%、引用有效率 100%，首版除安全与证据一致性硬门禁外均在报告中显式标注达标状态。普通 Surefire 测试使用 mock/fake 覆盖算法与协议，不激活 Profile、不访问外部系统。

### 7. 静态前端保持零构建并对动态内容统一转义

`static/index.html` 增加知识库选择和管理区域；`app.js` 通过既有 `apiCall` 调用所有非流式管理接口，维护当前知识库，解析 `citations` SSE，并在答案下渲染可展开引用卡片；`style.css` 增加状态与引用样式。来源名、摘要、错误文本等动态值必须复用转义函数，不能拼接未转义 HTML。历史详情与续聊只渲染接口返回的引用快照。每次静态资源变更同步递增缓存版本，不引入 npm、CDN 或构建工具。

停用知识库仍保留在管理下拉框中，允许用户查看文档、重新启用或删除空库；问答、搜索、上传和替换在前端发起请求前校验当前库必须为 `active`。从历史会话恢复知识库时仍只接受 `active` 状态，停用或删除的历史库继续进入“不可用、等待重新选择”状态。

知识库列表加载失败时清空内存中的旧列表和当前知识库 ID，在重新加载并选择有效知识库前阻止知识问答、搜索和文档写入继续使用上一次选择；已经渲染的历史引用快照不受影响。

## Risks / Trade-offs

- [MySQL 状态与 PgVector 写入无法原子提交] → 采用 processing/ready/failed 版本状态、检索资格过滤和定向补偿清理；把残留向量视为不可见垃圾而不是可用证据。
- [过采样窗口仍可能被大量残留版本占满] → 每次成功/失败都定向清理，过采样上限 24；监控补偿失败，后续可增加独立清理任务但不在本迭代引入队列。
- [旧向量在 4.0.0 升级后不可检索] → 明确标为 breaking change，保留 `/api/load`、`/api/upload` 作为默认库重新导入入口，并在部署前执行重导清单。
- [新旧模型同为 384 维但语义空间不兼容] → 为新向量增加模型身份并在检索过滤中强制匹配；发布后逐文档重新导入，不以维度相同为由混用历史向量。
- [旧文档在 MySQL 中仍为 ready 但不属于当前模型] → 成功晋级时记录模型身份，旧记录保持为空并通过 `requiresReindex` 在接口和页面提示重新导入，不把旧向量误报为当前模型可检索。
- [模型可能生成伪造或遗漏引用编号] → 以后端召回白名单解析，伪造编号丢弃；没有合法编号时返回空数组，绝不猜测。
- [引用 JSON 增大历史表] → 限制为 8 条、500 字符摘要和 32 KiB；保存的是展示快照而不是完整正文。
- [真实 LLM 评测存在波动和费用] → 显式 Profile、固定数据集与温度配置、独立报告 token/成本；首版 Judge 不阻塞默认构建。
- [评测绕过计费可能被误用于生产] → Runner 只位于测试源码并由 `rag-eval` Profile 激活；生产 Controller 无免计费入口。
- [知识库停用/删除与进行中问答竞争] → 请求开始时解析一次有效知识库，检索资格过滤再次校验；停用后的新请求拒绝，已经持久化的历史引用保持不变。

## Migration Plan

1. 先部署幂等 MySQL DDL：新增知识库/文档表与 `a_chat_message` 可空字段；旧应用忽略新表和新字段，允许数据库先行。
2. 部署 4.0.0 后，为每个首次使用知识能力的租户懒创建默认知识库；旧历史消息按 `knowledgeBaseId = null`、`citations = []` 读取。
3. 盘点仍需使用的旧 `ent_code/source` 文档和 `all-MiniLM-L6-v2` 向量，通过 `/api/load` 或 `/api/upload` 重新导入默认知识库；在完成前明确接受这些旧向量不参与新模型检索。
4. 运行离线单元/协议测试，再在隔离环境执行 `mvn -Prag-eval verify`，确认隔离与引用硬门禁、质量目标和成本报告。
5. 前后端同步发布，刷新静态资源缓存版本；观察 failed 版本、向量补偿日志、引用有效率和 P95 延迟。

回滚应用时保留新增表/字段，不做破坏性 DDL；旧版本仍可忽略这些字段。旧版后端恢复知识检索前必须暂停知识导入，按完整稳定身份导出并备份 4.0.0 新格式向量，再将其从旧版访问的活动向量表中隔离或移除；随后恢复完整的发布前 PgVector 快照，或只恢复经确认的旧格式向量。不得让旧版后端直接读取新格式向量，也不得在重新导入后仅按 `ent_code + source` 清理。再次升级到 4.0.0 时恢复已备份的新格式向量或按原始文件重新导入。

## Open Questions

- 首版无待定的阻塞性设计问题。Provider 单价由配置维护，缺少单价时报告 token 并将成本标记为不可用，不能按 0 成本误报。
- 独立残留向量清理任务、文档版本回滚和在线评测后台留待后续 change；本迭代仅保留日志与可扩展接口边界。
