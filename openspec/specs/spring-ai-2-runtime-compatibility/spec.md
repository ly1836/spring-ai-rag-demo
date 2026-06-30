# spring-ai-2-runtime-compatibility Specification

## Purpose
TBD - created by archiving change upgrade-spring-ai-2. Update Purpose after archive.
## Requirements
### Requirement: 项目依赖必须升级到 Spring AI 2.x 兼容组合

系统 SHALL 使用 Spring AI 2.x 与 Spring Boot 4.0.x 兼容依赖组合构建，且 MUST 同步替换 Spring AI 2.x 中已重命名的 advisor artifact 和 Boot 4 专用的 MyBatis-Plus starter。系统 MUST 保留当前业务启用的 DeepSeek、OpenAI 兼容接口、Google GenAI 三类 Model starter，并更新为 Spring AI 2.x 对应 artifact；`ModelRegistry` MUST 仅保留 Spring AI 2.0.0 正式版已确认适配的 provider 映射，MUST NOT 将迁移目标理解为在 POM 中引入 Spring AI 1.1.4 的全部官方 Chat Model starter。升级后 Maven 依赖解析、编译和应用启动路径 MUST 不再依赖 Spring AI 1.1.x 或 Spring Boot 3.x 专用 artifact。

#### Scenario: Maven 构建使用 2.x 依赖组合

- **WHEN** 开发者执行项目 Maven 构建
- **THEN** `spring-ai.version` MUST 解析为 Spring AI 2.x 版本
- **AND** Spring Boot parent MUST 使用 4.0.x 版本
- **AND** DeepSeek、OpenAI 兼容接口、Google GenAI 的 Model starter MUST 使用 Spring AI 2.x 对应 artifact
- **AND** vector store advisor 依赖 MUST 使用 Spring AI 2.x artifact 名称
- **AND** MyBatis-Plus starter MUST 使用 Boot 4 兼容 artifact
- **AND** 构建目标 MUST NOT 要求在 POM 中引入 Spring AI 1.1.4 的全部官方 Chat Model starter

### Requirement: Spring AI 配置属性必须迁移且保持业务模型配置不变

系统 SHALL 将 provider 默认 chat model 配置迁移到 Spring AI 2.x 属性命名，同时 MUST 保留 `app.models` 作为业务层模型列表和模型路由来源。系统 MUST NOT 通过单一 `spring.ai.model.chat` 配置替代现有多模型切换设计。

#### Scenario: Provider 默认模型配置迁移到 2.x 命名

- **WHEN** 应用读取 `application.yml`
- **THEN** DeepSeek、OpenAI 兼容接口和 Google GenAI 的默认 chat model MUST 使用 Spring AI 2.x 支持的属性层级
- **AND** 配置中 MUST NOT 继续依赖 `chat.options.model` 作为 provider 默认模型属性

#### Scenario: 业务模型列表保持兼容

- **WHEN** 前端调用 `/api/models`
- **THEN** 系统 MUST 返回与升级前兼容的模型列表结构
- **AND** `deepseek-chat`、`deepseek-reasoner`、`qwen-max`、`qwen-turbo`、`gemini-2.0-flash` 的 `id`、`provider` 和 `model-name` 语义 MUST 保持不变

### Requirement: 多模型路由必须保持原有行为

系统 SHALL 继续通过 `ModelRegistry` 根据前端传入的 `modelId` 路由到对应 provider 的 `ChatModel`，并使用 `app.models[].model-name` 作为请求级模型名覆盖。业务代码 MUST NOT 直接注入具体 provider 的 ChatModel，也 MUST NOT 绕过 `ModelRegistry` 固定调用单一模型。

#### Scenario: 只保留 2.0.0 已确认适配的历史 provider 映射

- **WHEN** `app.models` 配置了升级前已有的历史 provider
- **THEN** `ModelRegistry` MUST 仅对 Spring AI 2.0.0 正式版已确认适配的 provider 保留映射
- **AND** 当对应 ChatModel bean 已由 Spring AI starter 注册时，系统 MUST 路由到该 bean
- **AND** 对 Spring AI 2.0.0 正式版未确认适配的历史 provider，系统 MUST 不把该 provider 误判为当前已启用能力

#### Scenario: DeepSeek 模型请求仍走 DeepSeek provider

- **WHEN** 用户选择 `deepseek-chat` 或 `deepseek-reasoner` 发起问答
- **THEN** 系统 MUST 通过 `ModelRegistry` 取得 DeepSeek ChatModel
- **AND** 请求级模型名 MUST 分别使用 `deepseek-chat` 或 `deepseek-reasoner`

#### Scenario: OpenAI 兼容模型请求仍走 OpenAI provider

- **WHEN** 用户选择 `qwen-max` 或 `qwen-turbo` 发起问答
- **THEN** 系统 MUST 通过 `ModelRegistry` 取得 OpenAI 兼容 ChatModel
- **AND** 请求级模型名 MUST 使用对应的 DashScope 模型标识

#### Scenario: Google GenAI 模型请求仍走 Google GenAI provider

- **WHEN** 用户选择 `gemini-2.0-flash` 发起问答
- **THEN** 系统 MUST 通过 `ModelRegistry` 取得 Google GenAI ChatModel
- **AND** 请求级模型名 MUST 使用 `gemini-2.0-flash`

### Requirement: 三种问答模式必须保持工具调用和 RAG 组合语义

系统 SHALL 在 Spring AI 2.x 中保持 auto、data、knowledge 三种问答模式的能力组合不变。tool calling 的全局配置 MUST 不破坏任一模式的局部语义。

#### Scenario: auto 模式同时启用工具、RAG 和记忆

- **WHEN** 用户以 auto 模式发起问答
- **THEN** 系统 MUST 注入 ChatMemory advisor
- **AND** 系统 MUST 注入 QuestionAnswerAdvisor 进行 RAG 检索
- **AND** 系统 MUST 允许 LLM 调用已注册的 ERP tools

#### Scenario: data 模式启用工具和记忆但不启用 RAG

- **WHEN** 用户以 data 模式发起问答
- **THEN** 系统 MUST 注入 ChatMemory advisor
- **AND** 系统 MUST 允许 LLM 调用已注册的 ERP tools
- **AND** 系统 MUST NOT 注入 QuestionAnswerAdvisor

#### Scenario: knowledge 模式启用 RAG 和记忆但禁用工具

- **WHEN** 用户以 knowledge 模式发起问答
- **THEN** 系统 MUST 注入 ChatMemory advisor
- **AND** 系统 MUST 注入 QuestionAnswerAdvisor 进行 RAG 检索
- **AND** 系统 MUST NOT 暴露或执行任何 ERP tool

### Requirement: ChatMemory 必须保持历史上下文兼容

系统 SHALL 继续使用当前自定义 `JdbcChatMemoryRepository` 为 Spring AI ChatMemory 提供历史消息，并保持每个会话最多加载 20 条最近成功消息的窗口语义。系统 MUST 保留避免当前用户问题重复进入上下文的处理逻辑。

#### Scenario: 连续对话不会重复注入当前用户问题

- **WHEN** 用户在同一 `conversationId` 下连续发起第二轮问答
- **THEN** 系统 MUST 在调用 LLM 前保存当前用户消息
- **AND** ChatMemory 加载历史时 MUST NOT 将当前用户消息重复注入到本轮 prompt 上下文

#### Scenario: 历史续聊仍按会话 ID 加载上下文

- **WHEN** 用户从历史记录继续一个有效会话并发起问答
- **THEN** 系统 MUST 使用该 `conversationId` 加载对应历史消息
- **AND** 后续助手回复 MUST 继续保存到同一个会话

### Requirement: RAG 和 PgVector 行为必须保持兼容

系统 SHALL 继续使用本地 transformer embedding 和 PgVector 向量库执行文档检索。embedding 输出维度、PgVector dimensions、相似度阈值、topK 和 `ent_code` 元数据过滤 MUST 与升级前保持一致，除非后续 change 明确要求重建向量库。

#### Scenario: RAG 检索按租户过滤

- **WHEN** 用户在 auto 或 knowledge 模式下发起需要知识库检索的问题
- **THEN** 系统 MUST 使用当前 `TenantContext` 中的 `ent_code` 构造向量检索过滤条件
- **AND** 检索结果 MUST 只包含当前租户导入的文档片段

#### Scenario: 向量维度保持 384

- **WHEN** 应用启动并初始化 PgVector VectorStore
- **THEN** PgVector dimensions MUST 保持为 384
- **AND** 本地 transformer embedding 模型 MUST 继续使用项目 resources 中的 ONNX 模型与 tokenizer

### Requirement: 计费扣费和对外 API 语义必须保持兼容

系统 SHALL 在升级后保持 LLM 调用前配额检查、调用后 token 扣费、交易流水、每日用量聚合和错误处理语义不变。现有非流式 API MUST 继续返回 `RespVO<T>`，流式问答 MUST 继续返回 SSE 消费兼容的 `Flux<String>`。

#### Scenario: 非流式问答仍执行计费前后置流程

- **WHEN** 用户调用非流式问答接口并成功获得模型响应
- **THEN** 系统 MUST 在 LLM 调用前执行 `BillingService.checkQuota()`
- **AND** 系统 MUST 在获得 usage 后执行 `BillingService.deductTokens()`
- **AND** 系统 MUST 保存用户消息、助手消息和 token 使用量

#### Scenario: 流式问答接口保持 SSE 行为

- **WHEN** 用户调用 `GET /api/ask/stream`
- **THEN** 系统 MUST 继续返回可被现有前端解析的流式文本片段
- **AND** 响应 MUST NOT 被包装成 `RespVO`

### Requirement: ERP tools 必须保持租户隔离和 SQL 访问边界

系统 SHALL 保持所有 ERP `@Tool` 查询通过 `BaseTool` 执行，并继续自动追加当前租户的 `ent_code` 条件。升级 Spring AI tool calling 机制时 MUST NOT 新增绕过 `BaseTool` 的 ERP SQL 查询路径。

#### Scenario: 工具调用查询自动带租户条件

- **WHEN** LLM 在 auto 或 data 模式下调用任意 ERP tool
- **THEN** tool 查询 MUST 通过 `BaseTool.query()` 或 `BaseTool.queryWithAlias()` 执行
- **AND** SQL 参数中 MUST 包含当前请求的 `ent_code`

#### Scenario: knowledge 模式不会访问 ERP tools

- **WHEN** 用户以 knowledge 模式提出可能触发 ERP 查询的问题
- **THEN** 系统 MUST 不向 LLM 暴露 ERP tool
- **AND** 系统 MUST 不执行任何 ERP SQL 查询

