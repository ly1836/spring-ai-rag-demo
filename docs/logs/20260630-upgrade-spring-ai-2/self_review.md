# 自查报告

- Change ID: upgrade-spring-ai-2
- Latest Review Time: 2026-06-30 16:35:08
- 变更范围：当前可见 git diff 覆盖 `pom.xml`、`application.yml`、`ErpAssistantService`、`ModelRegistry`、`DataSourceConfig`、README/README_EN、OpenSpec 主 spec 与归档材料、新增/更新测试文件；目标是将项目迁移到 Spring AI 2.0.0 / Spring Boot 4.0.7，并保持原 ERP RAG 问答能力兼容。
- OpenSpec 材料：已读取归档后的 `openspec/changes/archive/2026-06-30-upgrade-spring-ai-2/proposal.md`、`design.md`、`tasks.md`、`specs/spring-ai-2-runtime-compatibility/spec.md`；主 spec 已同步到 `openspec/specs/spring-ai-2-runtime-compatibility/spec.md`；`.openspec.yaml` 创建日期为 2026-06-30。

## 执行记录

| 时间 | 变更范围摘要 | 结论 |
| --- | --- | --- |
| 2026-06-30 15:50:08 | 自查 Spring AI 2 依赖、配置、ChatClient/Advisor 适配、多模型路由、no-tools knowledge 模式、Controller 接口形态测试和 OpenSpec 文档一致性 | 通过 |
| 2026-06-30 16:35:08 | 归档后复查主 spec 同步、任务清单外部/手工项清理、最新测试和提交门禁日志 | 通过 |

## 问题清单

| 状态 | 严重级别 | 文件/行号 | 问题 | 建议 |
| --- | --- | --- | --- | --- |
| 已解决 | 低 | `src/test/java/com/example/rag/chat/ChatControllerTest.java` | 原报告指出接口验证缺口较大，`/api/models`、非流式问答、流式问答和文档搜索接口形态缺少自动化证据。 | 已新增 `ChatControllerTest` 覆盖 `/api/models` 的模型列表和 `RespVO` 包装、`/api/ask` 的非流式 `RespVO<AskResponse>`、`/api/ask/stream` 的 SSE `Flux` 和 `X-Conversation-Id`、`/api/search` 的搜索响应结构；最新 `mvn test` 25 个测试通过。 |

## OpenSpec 一致性

- 依赖升级符合 spec：`pom.xml` 将 `spring-ai.version` 升级到 `2.0.0`，Spring Boot parent 升级到 `4.0.7`，vector store advisor 使用 `spring-ai-vector-store-advisor`，MyBatis-Plus 使用 Boot 4 starter。
- 配置迁移符合 spec：`application.yml` 将 DeepSeek/OpenAI/Google GenAI 的 provider 默认模型配置迁移到 2.x 支持的 `chat.model` 层级，并显式选择 `spring.ai.model.embedding=transformers`，保留本地 384 维 embedding 和 `app.models` 业务路由。
- 多模型路由符合最新决策：`ModelRegistry` 保留当前实际启用的 `deepseek`、`openai`、`google-genai`，并只保留 Spring AI 2.0.0 正式版已确认适配的 `anthropic`、`ollama`、`mistral`、`bedrock` 映射；未确认适配的历史 provider 未进入可路由表。
- 三种问答模式符合 spec：auto 保留 ChatMemory + RAG + tools；data 保留 ChatMemory + tools 且不注入 RAG；knowledge 使用 no-tools ChatClient，并通过 `ChatClientAttributes.TOOL_CALLING_ADVISOR_AUTO_REGISTER` 禁用工具 advisor，同时保留 ChatMemory + RAG。
- ChatMemory 一致性符合 spec：调用处使用 `ChatMemory.CONVERSATION_ID` 传递 conversation id；自定义 repository 去除尾部当前 `UserMessage` 的策略未改。
- OpenSpec 归档一致：`openspec archive upgrade-spring-ai-2 --yes` 已创建主 spec 并归档 change；按用户要求移除了归档 `tasks.md` 中依赖真实外部环境的手工回归任务，当前任务清单无未完成项。

## 非功能审查

- 并发：本次未引入新的共享可变业务状态；`ModelRegistry` 和 `ErpAssistantService` 的 provider client 缓存继续使用 `ConcurrentHashMap`。未发现新增竞态或幂等边界变化。
- 安全：未新增 API Key 日志、鉴权绕过或 SQL 拼接路径；ERP tools 仍通过 `BaseTool` 访问 ERP SQL。knowledge 模式新增 no-tools 路径降低误调用 ERP tools 的风险。
- 边界：`getChatModel(modelId)` 在 provider 未映射或 bean 不存在时返回 null，调用方已有默认模型兜底路径；`getClient(modelId)` 对未注册 provider 会回退默认客户端。该行为符合当前设计。
- 性能：未新增数据库查询、循环扫描或阻塞 I/O；主要变化是 ChatClient 构建缓存和 advisor 参数适配，未发现新增性能热点。
- 回归风险：接口形态已通过 `ChatControllerTest` 补充自动化覆盖；本地启动和基础接口访问已验证，未发现代码级回归风险。

## 测试缺口

- 单元测试缺口：无新增代码级测试缺口。
- 接口测试缺口：无阻塞缺口，`ChatControllerTest` 和本地 curl 已覆盖本次提交需要证明的接口形态与基础访问。

## 结论

- 结果：通过
- 摘要：代码、OpenSpec 主 spec 与归档材料对当前 Spring AI 2.0.0 迁移目标一致；未完成外部/手工 task 已按用户要求移除，自动化验证和本地基础启动验证通过。
