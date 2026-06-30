## Context

当前应用是单模块 Spring Boot ERP RAG 智能助手，Spring AI 版本为 1.1.4，Spring Boot 版本为 3.5.12。核心 AI 编排集中在 `ErpAssistantService`，通过 `ChatClient`、`MessageChatMemoryAdvisor`、`QuestionAnswerAdvisor`、`ModelRegistry`、`VectorStore` 和 `BaseTool` 组合出 auto/data/knowledge 三种问答模式。

本次变更不是新增业务能力，而是运行时升级：将项目迁移到 Spring AI 2.x 与 Spring Boot 4.0.x，并保持对外接口、问答模式、多模型路由、ChatMemory、PgVector、本地 embedding、计费扣费、租户隔离和 ERP SQL 访问语义不变。

现有外部接口保持不变：

- `GET /api/ask`
- `GET /api/ask/stream`
- `POST /api/upload`
- `POST /api/load`
- `GET /api/search`
- `GET /api/models`
- `GET /api/hints`
- `/api/billing/**`
- `/api/conversations/**`

这些接口的请求参数、`RespVO<T>` 包装、SSE `Flux<String>` 返回、错误码语义和前端调用方式均不作为本次变更范围。

## Goals / Non-Goals

**Goals:**

- Maven 依赖升级到 Spring AI 2.x、Spring Boot 4.0.x 和 Boot 4 兼容的 MyBatis-Plus starter，并保留当前启用 provider 对应的 Spring AI 2.x Model starter。
- Spring AI 配置项迁移到 2.x 推荐命名，保留 `app.models` 多模型配置。
- `ErpAssistantService` 适配 Spring AI 2.x 的 ChatClient options、ChatMemory advisor 参数和工具调用机制。
- `ModelRegistry` 保留当前实际启用的 DeepSeek、OpenAI 兼容接口、Google GenAI provider，并保留 Spring AI 2.0.0 正式版已确认适配的历史 provider 映射。
- auto/data/knowledge 三种问答模式、RAG 检索、ChatMemory 上下文、token 计费和会话持久化行为保持一致。
- PgVector 主数据源、ERP MySQL 数据源、384 维本地 transformer embedding 和 `ent_code` 隔离行为保持一致。
- 编译、OpenSpec 校验和可用的本地自动化测试通过。

**Non-Goals:**

- 不新增 API、前端页面、问答模式、工具类或业务表。
- 不切换 embedding 模型，不重建向量表，不修改 PgVector dimensions。
- 不改造计费模型、价格规则、扣费公式或交易流水结构。
- 不将多模型路由改成单 provider 的 `spring.ai.model.chat` 模式。
- 不修改 ERP tools 的 SQL 语义，不放宽租户隔离。
- 不处理 Spring AI 2.x 之外的功能升级，例如新增 provider、替换 LLM、调整 Prompt 风格。
- 不在 POM 中引入 Spring AI 1.1.4 的全部官方 Chat Model starter；Spring AI 2.0.0 正式版未确认适配的历史 provider 不进入当前路由映射。

## Decisions

### 使用 Spring Boot 4.0.x，而不是 4.1.x

实施时选择当前可用的最新 Spring Boot 4.0.x patch 版本，并将 `spring-ai.version` 升级到 `2.0.0`。选择 4.0.x 是为了满足 Spring AI 2.x 的 Boot 4 生态要求，同时避免把 Boot 4.1.x 的新增变化混入本次迁移。

备选方案是直接升到当前最新 Boot 4.1.x。该方案短期变量更多，可能引入与 Spring AI 迁移无关的自动配置差异，因此不采用。

### 保留 `ModelRegistry + app.models` 多模型路由

`app.models` 继续作为前端模型下拉和后端模型路由的唯一业务配置来源。`ModelRegistry` 继续根据 `modelId` 找到 provider，再取对应 ChatModel 构建 ChatClient，请求级模型名继续来自 `model-name`。

不新增 `spring.ai.model.chat=deepseek` 这类单模型选择配置。该配置适合单 provider 默认模型模式，但会弱化当前项目已有的多模型切换能力。

### 配置迁移只调整 Spring AI 2.x 属性，不改变业务配置

`spring.ai.deepseek.chat.options.model`、`spring.ai.openai.chat.options.model`、`spring.ai.google.genai.chat.options.model` 迁移为 Spring AI 2.x 的 chat model 属性层级。`app.models`、PgVector、transformer embedding、datasource、retry 和 server 配置保持原语义。

如果升级后 Spring AI 不再默认设置某些 chat options，例如 temperature，则在配置中显式补齐原行为依赖的值。未被原功能依赖的采样参数不新增。

### ChatClient 调用适配 2.x API，但不改变三种模式语义

非流式和流式问答都继续使用 `ChatOptions.builder().model(modelName)` 进行请求级模型名覆盖。若 2.x `ChatClient.options()` 接受 builder 而不是 build 后对象，则同步调整调用方式。

advisor 组装保持语义：

- auto：ChatMemory + RAG + tools
- data：ChatMemory + tools
- knowledge：ChatMemory + RAG，无 tools

knowledge 模式不通过全局关闭 tool calling 实现，而是构建不带默认 tools 的 ChatClient 或使用 2.x 支持的局部禁用方式，避免影响 auto/data 模式。

### ChatMemory 继续使用自定义 `JdbcChatMemoryRepository`

`JdbcChatMemoryRepository` 继续作为 ChatMemory 数据来源，并保留当前加载历史时移除尾部当前 `UserMessage` 的逻辑，避免 `prepareConversation()` 先写入当前用户问题后又被 memory advisor 重复注入上下文。

若 Spring AI 2.x 对 conversation id 参数传递方式有变化，则使用 `ChatMemory.CONVERSATION_ID` 这类 advisor 参数传递方式适配，而不是改变 repository 的持久化策略。

### 保留 Spring AI 2.0.0 已确认适配的 provider 映射

`ModelRegistry` 保留当前配置实际引用的 provider：

- `deepseek`
- `openai`
- `google-genai`

本次依赖迁移必须保留并更新上述 provider 对应的 Spring AI 2.x Model starter：`spring-ai-starter-model-deepseek`、`spring-ai-starter-model-openai`、`spring-ai-starter-model-google-genai`。同时，`ModelRegistry` 可保留 Spring AI 2.0.0 正式版已确认适配的历史 provider → ChatModel bean 名称映射，当前包括 `anthropic`、`ollama`、`mistral`、`bedrock`。

`azure-openai`、`vertex-ai-gemini`、`minimax`、`zhipu`、`huggingface`、`oci-genai` 等历史 provider 在 Spring AI 2.0.0 正式版中未确认存在对应 GA starter 或 ChatModel bean 名称，因此不进入当前可路由表。后续若升级到包含这些 provider 的 Spring AI 2.x 版本，应先核对 starter 坐标和 AutoConfiguration bean 名称，再追加映射。

provider 映射只有在 POM 中引入对应 Spring AI 2.x starter，并由 AutoConfiguration 注册出对应 ChatModel bean 时才会生效。未引入 starter 或未注册 bean 的 provider 仍回退到默认客户端或返回 null，不得影响当前三个已启用 provider 的路由和 `/api/models` 返回。

### 不修改数据结构

本次升级不新增数据库 migration。PgVector 表、`a_chat_conversation`、`a_chat_message`、计费表、ERP 业务表结构都保持不变。

如 Spring AI 2.x 官方默认 JDBC ChatMemory schema 与当前自定义表结构不同，不迁移到官方默认 schema；继续使用项目自定义 repository 适配当前表。

## Risks / Trade-offs

- Spring AI 2.x ChatClient/Advisor API 差异导致编译失败 → 通过逐项编译错误收敛，优先选择最小 API 迁移，不重写业务编排。
- tool calling 默认链路改变导致 knowledge 模式误调用 tools → 增加 knowledge 模式 no-tools 客户端或局部禁用路径，并用回归用例验证工具不执行。
- ChatMemory conversation id 参数变化导致上下文丢失或重复 → 保留 repository 去重逻辑，并验证连续两轮对话不会重复当前问题。
- Spring AI 配置属性迁移后 provider bean 未创建 → 启动时验证 DeepSeek、OpenAI、Google GenAI ChatModel bean 存在，`/api/models` 与实际路由一致。
- PgVector 或 embedding 自动配置变化导致维度不一致 → 保留 384 维配置，启动和文档检索路径必须验证向量库可用。
- Boot 4 / Jackson 3 影响 JSON 序列化 → 编译和接口 smoke test 覆盖 `RespVO`、VO record、SSE 非包装返回。
- MyBatis-Plus Boot 4 starter 与当前双数据源配置不兼容 → 编译与启动验证 ERP mapper 仍绑定 MySQL，PgVector 仍为 `@Primary`。

## Migration Plan

1. 修改 `pom.xml` 的版本与 artifact，先保证 Maven 依赖解析走 Spring AI 2.x、Boot 4.0.x 和 MyBatis-Plus Boot 4 starter。
2. 修改 `application.yml` 的 Spring AI chat model 属性，保留 `app.models`、PgVector、embedding 和 datasource 配置。
3. 修改 `ErpAssistantService`，适配 ChatClient options、memory advisor conversation id 参数和 knowledge no-tools 客户端。
4. 修改 `ModelRegistry`，保留当前启用 provider 和 Spring AI 2.0.0 已确认适配的历史 provider 映射。
5. 编译并修复 Spring AI 2.x API 差异带来的最小必要代码问题。
6. 运行 OpenSpec 校验、Maven 编译和可用测试。
7. 手工或集成验证 `/api/models`、auto/data/knowledge 三种模式、RAG 搜索、历史续聊、计费扣费和租户隔离关键路径。

回滚策略：该变更集中在依赖、配置和 AI 编排适配。若升级后关键 provider 或 ChatMemory 无法稳定工作，回滚本 change 的 POM/YAML/Java 改动即可恢复 1.1.4 行为；不涉及数据库结构回滚。

## Open Questions

无。用户已确认版本组合、多模型保留、功能清理边界、工具调用、ChatMemory、PgVector、MyBatis-Plus 和 Jackson 行为均按“与原功能保持一致”处理。
