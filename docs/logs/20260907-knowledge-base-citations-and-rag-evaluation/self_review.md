# 自查报告

- Change ID: knowledge-base-citations-and-rag-evaluation
- Latest Review Time: 2026-09-08 23:50:29
- 变更范围：当前暂存区覆盖知识库与文档版本管理、受管 RAG 检索、回答引用、SSE 与历史回放、RAG 评测、数据库初始化、静态前端、本地嵌入模型与分词器、迁移说明、主规范同步、OpenSpec 归档及三份 change 日志；Dockerfile 与 docker-compose 保持原样，共 132 个文件纳入最终提交范围。
- OpenSpec 材料：已完整读取归档目录中的 `proposal.md`、`design.md`、`tasks.md` 和六份 delta spec；122/122 项任务完成，6 份增量规范已同步到主规范，change 已归档到 `openspec/changes/archive/2026-09-08-knowledge-base-citations-and-rag-evaluation/`。

## 执行记录

| 时间 | 变更范围摘要 | 结论 |
| --- | --- | --- |
| 2026-09-08 15:50:17 | 对照 OpenSpec 审查 `HEAD fbef5dc` 到当前工作区的完整变更，复核知识库、文档版本、RAG 引用、历史与 SSE、租户隔离、异常分类、并发锁和 Spring Bean 依赖 | 有风险通过 |
| 2026-09-08 15:55:33 | 将最终代码、OpenSpec 更新和三份日志统一纳入暂存区，复核无 `MM`、`AM`、未暂存或未跟踪文件，并检查暂存差异 | 通过 |
| 2026-09-08 20:05:10 | 复核嵌入模型替换后的旧文档状态、数据库升级、检索过滤、前端提示、公共说明和报告数据，并在本地容器重新导入两份旧文档 | 通过 |
| 2026-09-08 21:42:12 | 按当时 128 个暂存文件复审知识库、文档导入、RAG、历史、前端、本地模型资源和部署说明，重新核对新增 Spring Bean 依赖及原业务边界 | 通过 |
| 2026-09-08 23:21:25 | 保持 Dockerfile 和 docker-compose 不变，修正模型资源发布校验说明，并按当前 126 个文件、122 项任务和 343 项本地测试更新三份 change 记录 | 通过 |
| 2026-09-08 23:50:29 | 复核主规范同步与归档后的 132 个暂存文件，重新执行 343 项本地测试、仓库密钥扫描、OpenSpec/LFS/Git 检查，并验证新本地镜像和容器启动状态 | 通过 |

## 问题清单

未发现新的业务逻辑、循环依赖、租户隔离或安全阻塞问题；本轮发现的模型构建校验说明不准确和报告数据过期均已处理。

| 状态 | 严重级别 | 文件/行号 | 问题 | 建议 |
| --- | --- | --- | --- | --- |
| 已解决 | 中 | `N/A（git status）` | 暂存区此前不是最终代码状态，19 个文件同时存在已暂存和未暂存内容，只提交旧暂存区会遗漏最新修复。 | 已将最终代码、OpenSpec 更新和三份日志统一纳入暂存区；当前无未暂存、未跟踪或混合状态文件，`git diff --cached --check` 通过。 |
| 已解决 | 中 | `src/main/java/com/example/rag/knowledge/KnowledgeDocumentService.java:302` | 旧文档在数据库中仍显示 `ready`，但向量属于旧嵌入模型，页面会让人误以为可以正常问答，实际检索为空。 | 已记录成功导入时使用的模型，旧记录返回 `requiresReindex=true` 并显示“需重新导入”；检索只接受当前模型版本，两份现有文档已用原文件重新导入。 |
| 已解决 | 中 | `src/main/java/com/example/rag/knowledge/KnowledgeDocumentIngestionService.java:276` | 文档解析、嵌入或向量写入阶段的基础设施故障曾沿用 `IllegalStateException`，可能被当作业务错误返回。 | 已新增 `KnowledgeInfrastructureException`，基础设施故障统一返回安全的 `SYSTEM_ERROR`，参数错误和版本状态冲突继续保持原错误语义。 |
| 已解决 | 中 | `src/test/java/com/example/rag/rageval/RagEvaluationIT.java:708` | 评测流程清理失败曾只写日志，报告仍可能显示通过，残留随机租户或向量数据。 | 已让清理步骤失败后继续尝试其余清理，把最终失败项写入 JSON/Markdown 报告并纳入硬门禁。 |
| 已解决 | 中 | `src/main/resources/models/embedding/MODEL.md:14`、`docs/4.0.0-rag-migration.md:41` | 模型说明曾要求同步更新并不存在的 Dockerfile 模型校验，容易让维护者误以为 Docker 构建会自动检查 LFS 资源完整性。 | 按用户决定保持 Dockerfile 不变；说明改为发布前先执行 LFS 对象检查和 `EmbeddingModelResourceTest`，模型哈希只在资源测试中维护。 |
| 已解决 | 低 | `src/main/java/com/example/rag/knowledge/KnowledgeBaseService.java:447` | 已存在唯一可用默认知识库时，普通列表和缺省知识库读取仍会锁租户行，增加同租户并发等待。 | 已增加无锁快速读取；只有首次创建、默认标记重复或状态异常时才进入租户锁修复。 |
| 已解决 | 低 | `src/test/java/com/example/rag/chat/AssistantBeanDependencyTest.java:32` | 新增知识库、RAG、导入和 Controller Bean 后需要防止构造器依赖形成闭环。 | 已扩展依赖图覆盖范围；人工依赖链复核和专项测试均未发现循环依赖。 |
| 已解决 | 低 | `openspec/config.yaml:15`、`openspec/context/spring-ai-rag-demo.md:28`、`README.md:28` | 公共说明仍写旧嵌入模型或旧 RAG 组件名，容易让后续维护按过期信息判断。 | 已统一为 `paraphrase-multilingual-MiniLM-L12-v2` 和 `RetrievalAugmentationAdvisor`。 |
| 已解决 | 低 | `docs/logs/20260907-knowledge-base-citations-and-rag-evaluation/` | 三份 change 日志中的文件数、任务数和测试数曾落后于最终代码。 | 已按同步主规范并归档后的当前暂存区更新为 132 个变更文件、122/122 项任务和 50 个测试类、343 项测试。 |

## OpenSpec 一致性

- 知识库和文档：新增租户隔离的知识库、稳定文档 ID、逐版本状态、替换、删除和默认库兼容入口，符合 `knowledge-base-management` 与 `knowledge-document-ingestion`。
- 导入流程：MySQL 登记、事务外解析/嵌入/向量写入、短事务晋级或失败补偿边界清楚；失败版本不会替换上一 `ready` 版本，迟到版本不会覆盖较新版本。
- 检索与引用：向量查询同时限定 `ent_code + knowledge_base_id`，再用 MySQL `ready` 当前版本过滤；引用来自同一轮实际证据，没有为生成引用执行第二次检索。
- 问答模式：`auto` 按需求接入受管 RAG 并保留业务 Tool，`data` 仍只使用业务 Tool，`knowledge` 仍不装配业务 Tool；模型路由、会话记录和单次计费流程保持原有边界。
- 协议与历史：非流式响应、SSE `citations` 事件、成功 `done`、历史引用快照和旧消息空引用降级均与 delta spec 一致。
- 兼容与迁移：`/api/load`、`/api/upload` 继续代理默认知识库；旧向量不自动进入 4.0.0 受管检索的边界已在迁移说明中明确。
- 模型迁移：文档版本只在成功入库后保存当前模型身份；旧 `ready` 记录不猜测、不补造，接口和页面明确要求重新导入，符合新增任务 10.55～10.56 和知识库管理场景。
- 模型资源：多语言 ONNX 模型与分词器均通过 Git LFS 纳入本地交付，发布前由 LFS 对象检查和本地资源测试分别校验完整性与固定 SHA-256；Dockerfile 保持通用 Maven 打包流程，符合任务 10.49、10.58 和发布约束。
- 评测：显式 `rag-eval` Profile、版本化 fixture、指标、报告和隔离/引用/完整性/清理硬门禁均已实现，默认 `mvn test` 保持离线。
- 规范归档：六份 delta spec 已同步到主规范，OpenSpec 当前无活动 change；归档目录保留 proposal、design、tasks、`.openspec.yaml` 和全部 delta spec。

## 非功能审查

- 并发：知识库写操作和文档版本晋级采用一致租户锁顺序；正常默认库读取不再持有行锁；流式收口使用单次完成标记，评测清理失败不会阻断后续清理步骤。
- 安全：知识库、文档、向量和引用均带租户及知识库边界；跨租户对象按不存在处理；前端新增来源、摘要和状态使用 `textContent` 或转义后渲染；基础设施错误不向接口返回内部原因；提交前仓库扫描未发现真实私钥、API Token、JWT、URL 内嵌凭据或误暂存的 `.env`。
- 边界：覆盖空文件、不支持格式、500MB/550MB、500 万字符、2 万分片、128 Token、100 条批次、`topK <= 24`、坏引用 JSON、旧向量、旧 `ready` 空模型身份和异常/取消流。
- 性能：受管检索最多过采样到 24 条并批量校验版本；知识库文档数使用数据库聚合；RAG 使用 Spring 托管共享执行器；默认知识库健康读路径避免不必要行锁。
- 回归风险：请求新增字段均可选，文档列表只新增布尔字段；`data` 模式不访问向量库，旧历史消息安全降级；Dockerfile 和 docker-compose 未改动；当前代码重新构建为本地 `4.0.0`，`latest` 指向同一镜像，应用容器启动成功且首页返回 HTTP 200。

## 测试缺口

- 本报告按用户要求只记录代码、OpenSpec 和本地审查结论；完整验证明细统一记录在同目录 `test_report.log`。
- 当前自查未发现需要新增代码修复的问题；完整验证明细和本地容器实测结果已记录在 `test_report.log`。

## 结论

- 结果：通过
- 摘要：全部 review 建议已落地；当前 132 个暂存文件与已同步、已归档的 OpenSpec 一致，Dockerfile 和 docker-compose 保持不变，原业务边界、租户隔离、新增 Bean 依赖、本地模型资源及提交敏感信息检查均未发现新增问题。
