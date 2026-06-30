## Why

涉及业务域：chat、conversation、tool、config、billing、vo。

当前项目仍基于 Spring AI 1.1.4 与 Spring Boot 3.5.12，已经落后于 Spring AI 2.x 的依赖、配置属性和 ChatClient/Advisor API 形态。需要升级到 Spring AI 2.x，同时保持原有 ERP RAG 智能助手的多模型路由、工具调用、RAG、ChatMemory、PgVector、计费和接口行为不变。

## What Changes

- 将 Maven 依赖组合升级到 Spring AI 2.x 与 Spring Boot 4.0.x，并同步调整已重命名或 Boot 4 专用的依赖 artifact。
- 保留当前业务启用的 DeepSeek、OpenAI 兼容接口、Google GenAI 三类 Model starter，并更新为 Spring AI 2.x 对应 artifact；`ModelRegistry` 仅保留 Spring AI 2.0.0 正式版已确认适配的 provider 映射，不要求在 POM 中引入 Spring AI 1.1.4 的全部官方 Chat Model starter。
- 调整 Spring AI 配置项命名，移除 1.1.x 风格的 `chat.options.model` 层级，保持 DeepSeek、OpenAI 兼容接口、Google GenAI 三类 provider 可用。
- 适配 Spring AI 2.x 的 ChatClient options、ChatMemory advisor 参数、工具调用 advisor 和 vector-store advisor 相关 API 差异。
- 保留 `ModelRegistry + app.models` 的多模型路由设计，不改成单一 `spring.ai.model.chat` 默认模型模式。
- 在不改变原功能的前提下保留 2.0.0 已确认适配的历史 provider 映射；2.0.0 正式版未确认适配的历史 provider 不进入可路由表，避免误导维护者。
- 保持 auto/data/knowledge 三种问答模式语义不变：auto 同时具备 tools 与 RAG，data 仅启用 tools 与记忆，knowledge 启用 RAG 与记忆但禁用 tools。
- 保持 ChatMemory、PgVector 384 维本地 embedding、计费扣费、租户隔离、ERP SQL 隔离和外部 API 响应结构不变。
- **BREAKING**：这是运行时依赖层面的升级，要求 Java/Spring Boot 生态切换到 Boot 4 兼容组合；对外接口不应产生破坏性变更。

## Capabilities

### New Capabilities

- `spring-ai-2-runtime-compatibility`: 定义项目升级到 Spring AI 2.x 后必须保持的依赖、配置、ChatClient、Advisor、多模型路由、工具调用、记忆、RAG、计费与租户隔离兼容要求。

### Modified Capabilities

- 无。现有业务能力的需求语义不变，本次 change 仅约束底层 Spring AI 2.x 运行时兼容与实现迁移。

## Impact

- 依赖与构建：`pom.xml` 中 Spring Boot、Spring AI BOM、当前启用 provider 的 Spring AI 2.x Model starter、MyBatis-Plus Boot starter 版本和 artifact 需要调整。
- 配置：`src/main/resources/application.yml` 中 Spring AI chat model 属性需要迁移到 2.x 命名；PgVector、transformer embedding、datasource、`app.models` 保持原语义。
- AI 编排：`src/main/java/com/example/rag/chat/ErpAssistantService.java` 需要适配 ChatClient options、memory advisor 参数、knowledge 模式禁用 tools 的实现方式。
- 多模型路由：`src/main/java/com/example/rag/chat/ModelRegistry.java` 需要保留已启用 provider，并只保留 Spring AI 2.0.0 已确认适配的历史 provider 映射。
- 会话记忆：`src/main/java/com/example/rag/conversation/JdbcChatMemoryRepository.java` 需要验证 Spring AI 2.x 接口兼容，保留“去掉尾部当前 UserMessage 避免重复上下文”的行为。
- 数据源与向量库：`src/main/java/com/example/rag/config/DataSourceConfig.java` 和 PgVector 自动配置应继续使用 `@Primary` PostgreSQL 数据源，ERP MySQL 仍由 `erpJdbcTemplate` 与 MyBatis-Plus 绑定。
- 风险评估：
  - 租户隔离：RAG 检索必须继续按 `ent_code` 过滤，ERP tools 必须继续通过 `BaseTool` 自动追加 `ent_code`。
  - 计费扣费：LLM 调用前后置 `BillingService.checkQuota()` / `deductTokens()` 流程不得改变。
  - ERP SQL：不新增或放宽任何直连 ERP SQL 路径；tools 行为应与升级前一致。
  - LLM Provider 切换：前端传入的 `modelId` 必须继续通过 `ModelRegistry` 路由到对应 ChatModel，`model-name` 继续作为请求级模型名覆盖。
