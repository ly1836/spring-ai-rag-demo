## 1. 依赖与配置迁移

- [x] 1.1 将 `pom.xml` 的 Spring Boot parent 升级到当前可用的 4.0.x patch 版本，并将 `spring-ai.version` 升级到 `2.0.0`
- [x] 1.2 将 `spring-ai-advisors-vector-store` 替换为 Spring AI 2.x 的 vector store advisor artifact，保留并更新 DeepSeek、OpenAI、Google GenAI 对应 Spring AI 2.x Model starter，并确认 `spring-ai-rag`、PgVector、transformers、Tika 相关依赖可解析
- [x] 1.3 将 `mybatis-plus-spring-boot3-starter` 替换为 Boot 4 兼容的 MyBatis-Plus starter，保持 `mybatis-plus.version` 与当前可用兼容版本一致
- [x] 1.4 将 `application.yml` 中 DeepSeek、OpenAI 兼容接口、Google GenAI 的 `chat.options.model` 属性迁移到 Spring AI 2.x 支持的 chat model 属性层级
- [x] 1.5 保留 `app.models`、PgVector 384 维、本地 transformer embedding、双数据源、retry 和 server 配置的原有语义，不新增 `spring.ai.model.chat` 单模型配置

## 2. ChatClient 与 Advisor 适配

- [x] 2.1 调整 `ErpAssistantService` 中所有 `ChatClient.options(...)` 调用，使请求级 `modelName` 覆盖方式符合 Spring AI 2.x API
- [x] 2.2 调整 ChatMemory advisor 的 `conversationId` 传递方式，使 auto、data、knowledge 三种模式继续按当前 `conversationId` 加载历史上下文
- [x] 2.3 保留 `JdbcChatMemoryRepository` 去掉尾部当前 `UserMessage` 的行为，并确认 Spring AI 2.x 接口方法签名兼容
- [x] 2.4 调整 auto 模式 advisor 组装，确保 ChatMemory、QuestionAnswerAdvisor 和 ERP tools 同时生效
- [x] 2.5 调整 data 模式 advisor 组装，确保 ChatMemory 和 ERP tools 生效且不注入 RAG advisor
- [x] 2.6 调整 knowledge 模式客户端构建，确保 ChatMemory 和 RAG 生效且不向 LLM 暴露任何 ERP tool

## 3. 多模型路由与 Provider 清理

- [x] 3.1 保留 `ModelRegistry` 对 `deepseek`、`openai`、`google-genai` 三个实际启用 provider 的路由能力
- [x] 3.2 在不影响 `/api/models` 和现有模型调用的前提下，保留 `ModelRegistry` 中 Spring AI 2.0.0 已确认适配的 provider 映射；2.0.0 正式版未确认适配的历史 provider 不进入当前可路由表
- [x] 3.3 确认 `ChatModelConfig` 仍以 DeepSeek 作为默认 `@Primary ChatModel`，且非默认 provider 仍由 `ModelRegistry` 按请求选择
- [x] 3.4 确认 `model-name` 继续作为请求级模型名覆盖，用于计费记录、日志和实际 LLM 调用

## 4. 数据源、向量库与业务兼容

- [x] 4.1 确认 PgVector 数据源仍为 `@Primary`，VectorStore 与 ChatMemory 默认使用 PostgreSQL `rag_demo`
- [x] 4.2 确认 ERP MySQL 仍由 `erpJdbcTemplate` 和 MyBatis-Plus mapper 绑定，且不会影响 PgVector 自动配置
- [x] 4.3 确认 RAG 检索仍通过 `FilterExpressionBuilder` 按当前 `ent_code` 过滤文档片段
- [x] 4.4 确认所有 ERP tools 仍继承 `BaseTool` 并通过 `query()` 或 `queryWithAlias()` 自动追加租户条件
- [x] 4.5 确认 `BillingService.checkQuota()` 和 `BillingService.deductTokens()` 在非流式与流式问答路径中的调用顺序和语义不变
- [x] 4.6 确认 `RespVO<T>`、VO record、SSE `Flux<String>` 在 Boot 4 / Jackson 3 组合下的序列化行为保持兼容

## 5. 自动化验证

- [x] 5.1 为 Spring AI 2.x 迁移补充或更新最小回归测试，覆盖多模型路由、knowledge 模式禁用 tools、ChatMemory conversation id 传递和当前用户消息不重复注入
- [x] 5.2 执行 `mvn test`，修复编译错误、单元测试失败和 Spring AI 2.x API 差异导致的问题
- [x] 5.3 执行 `mvn package -DskipTests`，确认完整打包路径可用且 resources 中本地 ONNX/tokenizer 文件未被过滤破坏
- [x] 5.4 执行 `openspec validate upgrade-spring-ai-2 --strict`，确认 proposal、design、specs、tasks 与 OpenSpec 规则一致
