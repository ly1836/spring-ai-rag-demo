> 涉及业务域：chat、conversation、vo、dao、init、static frontend；billing、tool、config 仅做兼容性与风险验证。

## Why

当前知识文档只有按文件名直接写入向量库的加载、上传和搜索能力，缺少稳定的知识库/文档身份、生命周期管理及可回放的回答引用；同时项目无法用可重复指标判断一次 RAG 修改究竟提升还是降低了质量。需要在同一迭代中建立“文档治理 → 可信检索证据 → 答案引用 → 质量评测”的闭环，使用户能够管理知识来源，开发者能够用同一条生产检索链路验证召回与回答质量。

## What Changes

- 新增租户隔离的知识库与文档注册能力，提供默认知识库、知识库启停、文档列表、稳定 `documentId`、版本、状态、分片数、错误摘要、删除和重新上传。
- 将向量元数据从 `ent_code + source` 扩展为包含 `knowledge_base_id`、`document_id`、`document_version`、`chunk_id` 和 `chunk_index` 的稳定证据标识；保留 `source` 兼容现有检索和展示。
- 文档导入在 MySQL 中先登记 `processing` 状态，在本地事务外完成 Tika 提取、切分和向量写入，最终转为 `ready` 或 `failed`；新版本写入失败时清理该版本分片，不得误删上一可用版本。
- 为问答和搜索请求增加可选 `knowledgeBaseId`，未指定时使用当前租户默认知识库；RAG 检索必须同时执行租户、知识库、文档状态和当前版本边界。
- 使用 Spring AI 模块化 RAG 组件收口生产检索链路，基于本轮实际召回文档构造编号上下文，并从响应元数据提取同一批证据；不得为生成引用再次执行向量检索。
- 非流式回答增加 `citations` 字段；流式回答增加位于 `delta` 之后、`done` 之前的 `citations` 事件；引用仅允许指向本轮实际召回的文档分片，非法或未使用的编号不得返回。
- 将知识库选择和引用快照持久化到助手消息，历史详情和续聊直接回放原引用，不重新检索，也不因文档后续替换或删除而改写历史证据。
- 修复现有助手消息 `rag_doc_count` 始终保存为 `0` 的行为，使其记录本轮实际召回且通过资格过滤的分片数。
- 扩展现有零构建前端：增加知识库选择、知识库/文档管理、文档状态和引用卡片；所有新增非流式请求继续通过统一 `apiCall`，动态值继续执行转义并递增静态资源缓存版本。
- 新增版本化 RAG 评测数据集，首批至少覆盖 50 个单文档、多文档、同义改写、无答案和租户/知识库隔离用例。
- 将偏英文的 `all-MiniLM-L6-v2` 替换为与 Spring AI 平均池化兼容、保持 384 维的 `paraphrase-multilingual-MiniLM-L12-v2`，为新向量写入模型身份并要求旧模型向量重新导入。
- 在文档版本表记录成功入库所使用的嵌入模型；旧记录未记录当前模型时，文档列表和前端必须明确提示“需重新导入”，避免数据库 `ready` 状态被误解为当前模型下可检索。
- 为受管检索增加当前知识库 `ready` 文档标题匹配和候选来源分散：问题明确包含文档标题时只检索该来源；普通语义检索在存在多个合格来源时优先保留来源多样性，避免超大文档占满最终 topK。
- 新增显式启用的 `rag-eval` Maven 评测 Profile，复用生产检索和知识回答组件，输出 Recall@5、MRR@5、空召回率、引用有效率、拒答率、关键事实命中率、延迟、Token、估算成本、相关性和事实一致性报告，并对 fixture 明确列出的关键错误结论执行确定性门禁。
- 默认 `mvn test` 保持离线、确定性且无需模型 Key；真实 PgVector/LLM 评测只在 `mvn -Prag-eval verify` 时运行，缺少依赖或 Key 时明确失败，不静默跳过。
- **BREAKING**：4.0.0 的受管知识库检索只接受包含稳定知识库、文档和版本元数据的新向量；升级前仅含 `ent_code/source` 的旧向量不会自动进入检索结果，需通过兼容上传入口重新导入默认知识库。
- 本迭代不引入 OCR、MinIO/S3、在线文档编辑、旧版本回滚、外部全文搜索引擎、LLM 重排、Query Rewrite、在线评测后台或新的认证授权体系；这些能力在可信基线建立后独立演进。

## Capabilities

### New Capabilities

- `knowledge-base-management`: 定义租户知识库、默认知识库、文档注册、状态、版本、删除、兼容入口和前端管理行为。
- `rag-answer-citations`: 定义知识库选择、生产检索证据、编号引用、非流式/SSE 返回、引用校验及历史快照语义。
- `rag-quality-evaluation`: 定义版本化评测集、隔离执行、指标、报告、基线回归门禁和显式 Maven Profile。

### Modified Capabilities

- `knowledge-document-ingestion`: 将导入覆盖边界从 `ent_code + source` 扩展为知识库和稳定文档版本，并增加注册状态及上一可用版本保护。
- `chat-history-resume`: 助手消息增加知识库选择和引用快照，历史详情与续聊必须原样回放可信引用。
- `chat-streaming-cancellation`: 类型化 SSE 增加 `citations` 事件，并定义成功、异常和取消时引用与 `done` 的发送边界。

## Impact

- **后端代码**：`chat/DocumentLoaderService`、`chat/ErpAssistantService`、`chat/client`、`chat/lifecycle`、新增知识库与 RAG 证据/评测职责类、`controller`、`conversation/ChatHistoryService`、`dao/entity`、`dao/mapper`、`vo` 和初始化器。
- **API**：新增 `/api/knowledge-bases` 及文档子资源接口；`/api/ask`、`/api/ask/stream`、`/api/search` 增加可选知识库参数和引用响应；保留 `/api/load`、`/api/upload` 的默认知识库兼容语义。
- **数据库**：MySQL 新增 `a_knowledge_base`、`a_knowledge_document`，`a_chat_message` 增加可空 `knowledge_base_id` 和 `rag_citations`；已有库通过幂等初始化迁移，旧消息和旧向量不要求自动反向补齐引用。
- **向量库**：PgVector 文档元数据增加稳定证据字段和 `embedding_model`；新导入按多语言 384 维模型工作，旧模型生成的向量即使维度相同也不会进入受管检索，必须重新上传生成新向量。
- **前端**：`static/index.html`、`app.js`、`style.css` 增加知识库、文档和引用交互，不引入 npm、构建工具或 CDN。
- **测试与构建**：增加评测 fixture、报告模型和 `rag-eval` Profile；普通测试不访问外部模型，显式评测使用独立随机租户/知识库数据并在结束后清理。
- **租户风险**：知识库元数据、向量过滤、引用、评测数据和删除条件均必须包含 `ent_code`，任何跨租户召回都属于硬失败。
- **计费风险**：正常问答继续经过现有 `BillingService` 前后置；评测不得创建用户会话或扣减租户余额，但必须在评测报告中单独记录真实 Token 和估算成本。
- **Provider 风险**：知识问答继续通过 `ModelRegistry` 路由；不同模型对编号引用和 LLM Judge 的遵循度不同，引用由后端白名单校验，LLM Judge 首版只报告不控制默认构建结果。
- **兼容性**：新增请求字段均可选，新增响应字段可空；旧历史消息按无知识库、空引用处理，现有内置前端与后端同步升级以识别 `citations` SSE 事件；旧向量必须重新导入，这是 4.0.0 明确接受的数据迁移边界。
