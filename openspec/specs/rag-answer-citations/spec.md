# rag-answer-citations Specification

## Purpose
TBD - created by archiving change knowledge-base-citations-and-rag-evaluation. Update Purpose after archive.
## Requirements
### Requirement: 知识问答和搜索必须绑定一个有效知识库

系统 SHALL 允许 `/api/ask`、`/api/ask/stream` 和 `/api/search` 接收可选 `knowledgeBaseId`。未提供时 MUST 使用当前租户默认知识库；提供时 MUST 校验知识库属于当前 `ent_code` 且状态为 `active`。

#### Scenario: 缺省使用默认知识库
- **WHEN** 当前租户发起 knowledge 问答或搜索但不传 `knowledgeBaseId`
- **THEN** 系统 MUST 使用该租户的默认知识库
- **AND** 响应 MUST 返回实际使用的 `knowledgeBaseId`

#### Scenario: 使用显式知识库
- **WHEN** 当前租户传入一个属于自己的 `active` 知识库
- **THEN** 检索 MUST 只在该知识库内执行
- **AND** 其他知识库的文档 MUST NOT 进入召回或引用

#### Scenario: 自动模式使用受管知识库
- **WHEN** 用户以 auto 模式发起同时包含 Tool Calling 和 RAG 的问答
- **THEN** RAG 部分 MUST 使用请求指定或当前租户默认的受管知识库
- **AND** Tool Calling、图表、会话和计费能力 MUST 保持可用
- **AND** 旧格式向量或其他知识库向量 MUST NOT 进入回答和引用

#### Scenario: 自动模式增强提示不得污染检索问题
- **WHEN** auto 模式为业务 Tool Calling 增加当前轮守卫提示或触发业务重试
- **THEN** 首次请求和重试的向量检索 MUST 始终使用用户原始问题
- **AND** 业务守卫提示 MUST 只发送给模型而不能进入向量查询文本

#### Scenario: 停用知识库被拒绝
- **WHEN** 问答或搜索请求指定状态为 `inactive` 的知识库
- **THEN** 系统 MUST 在调用向量库或 LLM 前返回 `BIZ_ERROR`

#### Scenario: 跨租户知识库被视为不存在
- **WHEN** 当前租户提交另一租户的 `knowledgeBaseId`
- **THEN** 系统 MUST 返回与知识库不存在一致的 `BIZ_ERROR`
- **AND** 系统 MUST NOT 调用向量库、LLM 或泄漏对象信息

#### Scenario: 搜索 topK 超过受管检索上限
- **WHEN** 调用方为受管知识搜索提交大于 24 的 `topK`
- **THEN** 系统 MUST 在访问知识库、向量库或 LLM 前返回 `PARAM_ERROR`
- **AND** 系统 MUST NOT 以候选窗口上限静默截断后返回少于请求数量的成功响应

### Requirement: RAG 检索必须执行租户、知识库和版本资格边界

系统 SHALL 使用同一生产检索组件先按 `ent_code + knowledge_base_id` 查询 PgVector，再仅保留 MySQL 中当前状态为 `ready` 的稳定文档版本。`processing`、`failed`、`superseded`、`deleted` 和旧格式向量 MUST NOT 进入最终上下文或引用。

#### Scenario: 只召回当前可用版本
- **WHEN** 同一稳定文档同时存在上一 `superseded` 版本和当前 `ready` 版本的残留向量
- **THEN** 系统 MUST 只把当前 `ready` 版本放入模型上下文
- **AND** `ragDocCount` MUST 只计算通过资格过滤的分片

#### Scenario: 写入中版本不可见
- **WHEN** 文档新版本仍为 `processing`
- **THEN** 该版本的已写入分片 MUST NOT 进入问答或搜索结果
- **AND** 上一 `ready` 版本 MAY 继续被召回

#### Scenario: 旧格式向量不可作为证据
- **WHEN** 向量仅含 `ent_code/source` 而缺少知识库、文档和版本标识
- **THEN** 系统 MUST 从受管知识库结果中排除该向量
- **AND** 系统 MUST NOT 为该向量伪造稳定引用身份

#### Scenario: 明确文档标题优先限定来源
- **WHEN** 用户原始问题在忽略扩展名、大小写、空白和标点后明确包含当前知识库唯一 `ready` 文档标题
- **THEN** 向量检索 MUST 在租户、知识库和当前嵌入模型过滤之外限定该文档来源
- **AND** 其他文档的高分但无关分片 MUST NOT 进入本轮上下文

#### Scenario: 普通检索保留候选来源多样性
- **WHEN** 普通语义检索的合格候选包含多个稳定文档且单个大文档占据多数高分位置
- **THEN** 最终 topK MUST 先保留各稳定文档的首条候选，再按原相似度顺序补齐
- **AND** 当合格候选只来自一个稳定文档时 MUST 正常返回该文档的多个分片

#### Scenario: 产品名被空格拆开
- **WHEN** 用户以 `rabbit mq` 这类“产品主体 + 常见缩写后缀”的空格形式提问
- **THEN** 系统 MUST 保留原问题并追加 `rabbitmq` 紧凑别名用于向量检索
- **AND** 系统 MUST NOT 引入 Tool 守卫提示或 LLM 改写文本

#### Scenario: 新旧嵌入模型向量不得混用
- **WHEN** PgVector 中同时存在当前多语言模型与旧 `all-MiniLM-L6-v2` 生成的同维度向量
- **THEN** 生产问答和搜索 MUST 只召回当前 `embedding_model` 的向量
- **AND** 管理员 MUST 通过重新导入为需保留文档生成新模型向量

### Requirement: 回答引用必须来自本轮同一批实际召回证据

系统 SHALL 使用 Spring AI 模块化 RAG 组件构造编号上下文，并从同一轮响应元数据提取实际召回文档。生成引用时 MUST NOT 再次执行向量检索，引用 MUST 经过本轮召回白名单校验。

#### Scenario: 同一批证据生成上下文和引用
- **WHEN** 知识问答召回若干合格分片并生成回答
- **THEN** 模型上下文和后端引用校验 MUST 使用同一批分片及相同编号
- **AND** 引用提取 MUST NOT 触发第二次 `similaritySearch`

#### Scenario: 模型引用合法编号
- **WHEN** 最终答案包含一个或多个能映射到本轮召回分片的 `[n]`
- **THEN** 系统 MUST 按编号在答案中首次出现的顺序返回去重引用

#### Scenario: 模型伪造编号
- **WHEN** 最终答案包含不存在、越界或不属于本轮召回的 `[n]`
- **THEN** 系统 MUST 丢弃非法编号
- **AND** 系统 MUST NOT 以相近来源或再次检索结果替代

#### Scenario: 模型没有合法引用
- **WHEN** 最终答案未包含任何可映射的合法编号
- **THEN** 系统 MUST 返回空 `citations`
- **AND** 回答文本 MAY 正常返回

#### Scenario: Markdown 代码字面量不作为引用
- **WHEN** 最终答案在转义文本、行内代码或围栏代码中包含 `[n]` 字面量
- **THEN** 系统 MUST 忽略这些编号并只校验正文中的真实引用
- **AND** 生产引用结果与真实评测的引用统计 MUST 使用同一提取规则

### Requirement: 引用对象必须稳定、有界且适合安全展示

系统 SHALL 为每条引用返回 `citationId`、`knowledgeBaseId`、`documentId`、`documentVersion`、`chunkId`、`chunkIndex`、`source`、`excerpt` 和 `score`。每轮最多返回 8 条引用，单条摘要最多 500 字符，持久化引用 JSON MUST 不超过 32 KiB。

#### Scenario: 返回完整引用身份
- **WHEN** 回答中存在合法引用编号
- **THEN** 每条引用 MUST 包含可定位到本轮受管文档版本和分片的稳定字段
- **AND** `source` MUST 保留原始展示名称

#### Scenario: 引用数量和摘要受限
- **WHEN** 回答使用超过 8 个合法编号或证据正文超过摘要上限
- **THEN** 系统 MUST 按首次出现顺序只保留前 8 条引用
- **AND** 每条 `excerpt` MUST 安全截断到最多 500 字符

#### Scenario: 引用快照超过总大小
- **WHEN** 序列化引用超过 32 KiB
- **THEN** 系统 MUST 继续缩减摘要或末尾引用直到满足上限
- **AND** 系统 MUST NOT 保存截断后不可解析的 JSON

### Requirement: 非流式知识回答必须返回引用和实际召回数量

系统 SHALL 在 `/api/ask` 的 `AskResponse` 中返回实际 `knowledgeBaseId`、`citations` 和资格过滤后的 `ragDocCount`。引用与回答 MUST 在同一请求内完成，原有 `answer`、`mode`、`conversationId` 和可空图表字段保持兼容。

#### Scenario: 非流式回答具有引用
- **WHEN** knowledge 模式非流式回答使用了合法证据编号
- **THEN** 成功响应 MUST 返回对应 `citations`
- **AND** `ragDocCount` MUST 等于本轮通过资格过滤的召回分片数

#### Scenario: 无证据回答保持兼容
- **WHEN** 本轮没有合格召回或答案没有合法引用
- **THEN** 成功响应 MUST 返回 `citations = []`
- **AND** `ragDocCount` MUST 为 `0` 或实际未被引用的合格召回数，不得固定写死为 `0`

### Requirement: 引用展示不得信任未转义的模型或文档内容

系统 SHALL 在静态前端把引用渲染为答案下方的可展开卡片。来源名和摘要 MUST 作为转义后的文本展示；点击引用 MUST NOT 直接执行文档内容、URL 或脚本。

#### Scenario: 展开引用卡片
- **WHEN** 非流式、流式或历史助手消息包含引用
- **THEN** 前端 MUST 在对应回答下展示编号、来源、版本和安全摘要
- **AND** 引用顺序 MUST 与回答内首次出现顺序一致

#### Scenario: 恶意来源或摘要
- **WHEN** `source` 或 `excerpt` 包含标签、事件属性或脚本片段
- **THEN** 前端 MUST 将其显示为普通文本
- **AND** 页面 MUST NOT 执行其中的内容
