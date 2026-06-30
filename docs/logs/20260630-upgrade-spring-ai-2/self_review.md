# 自查报告

- Change ID: upgrade-spring-ai-2
- Latest Review Time: 2026-06-30 17:21:17
- 变更范围：当前可见 git diff 覆盖 `README.md`、`README_EN.md`、`docker-compose.yml` 的镜像版本引用，将应用镜像从 `ly753/spring-ai-rag-demo:latest` 同步为 `ly753/spring-ai-rag-demo:2.0.0`；历史已审查范围覆盖 `pom.xml`、`application.yml`、`ErpAssistantService`、`ModelRegistry`、`DataSourceConfig`、README/README_EN、OpenSpec 主 spec 与归档材料、新增/更新测试文件，目标是将项目迁移到 Spring AI 2.0.0 / Spring Boot 4.0.7，并保持原 ERP RAG 问答能力兼容。
- OpenSpec 材料：已读取归档后的 `openspec/changes/archive/2026-06-30-upgrade-spring-ai-2/proposal.md`、`design.md`、`tasks.md`、`specs/spring-ai-2-runtime-compatibility/spec.md`；主 spec 已同步到 `openspec/specs/spring-ai-2-runtime-compatibility/spec.md`；`.openspec.yaml` 创建日期为 2026-06-30。

## 执行记录

| 时间 | 变更范围摘要 | 结论 |
| --- | --- | --- |
| 2026-06-30 15:50:08 | 自查 Spring AI 2 依赖、配置、ChatClient/Advisor 适配、多模型路由、no-tools knowledge 模式、Controller 接口形态测试和 OpenSpec 文档一致性 | 通过 |
| 2026-06-30 16:35:08 | 归档后复查主 spec 同步、任务清单外部/手工项清理、最新测试和提交门禁日志 | 通过 |
| 2026-06-30 17:10:44 | 复查 Docker 镜像 `2.0.0` 发布后的 README/README_EN/docker-compose 镜像引用同步、本地镜像 tag、空白错误和中文乱码扫描 | 通过 |
| 2026-06-30 17:15:49 | 补核 Docker Hub tag API，确认远端 `2.0.0` tag active、last_updated 和镜像 digest 可见 | 通过 |
| 2026-06-30 17:21:17 | 提交前复核当前 diff、日志结论、Maven 测试、打包、OpenSpec 严格校验、空白错误、乱码和未解决风险短语 | 通过 |

## 问题清单

| 状态 | 严重级别 | 文件/行号 | 问题 | 建议 |
| --- | --- | --- | --- | --- |
| 已解决 | 低 | `src/test/java/com/example/rag/chat/ChatControllerTest.java` | 原报告指出接口验证缺口较大，`/api/models`、非流式问答、流式问答和文档搜索接口形态缺少自动化证据。 | 已新增 `ChatControllerTest` 覆盖 `/api/models` 的模型列表和 `RespVO` 包装、`/api/ask` 的非流式 `RespVO<AskResponse>`、`/api/ask/stream` 的 SSE `Flux` 和 `X-Conversation-Id`、`/api/search` 的搜索响应结构；最新 `mvn test` 25 个测试通过。 |
| 已解决 | 低 | `N/A` | 原风险为 `docker manifest inspect docker.io/ly753/spring-ai-rag-demo:2.0.0` 连接 Docker Hub 超时，无法二次读取远端 metadata。 | 已通过 Docker Hub tag API 补核：`2.0.0` tag 为 active，`last_updated=2026-06-30T09:00:20.197487Z`，amd64 镜像 digest 为 `sha256:6d888314ad183c1153abd7c6a91828e758376c0f7b29cce34bdb6bda703e1cdf`，`last_pushed=2026-06-30T09:00:17.337918967Z`；Docker CLI manifest inspect 仍受当前网络影响，但远端 tag 可见性风险已关闭。 |

## OpenSpec 一致性

- 依赖升级符合 spec：`pom.xml` 将 `spring-ai.version` 升级到 `2.0.0`，Spring Boot parent 升级到 `4.0.7`，vector store advisor 使用 `spring-ai-vector-store-advisor`，MyBatis-Plus 使用 Boot 4 starter。
- 配置迁移符合 spec：`application.yml` 将 DeepSeek/OpenAI/Google GenAI 的 provider 默认模型配置迁移到 2.x 支持的 `chat.model` 层级，并显式选择 `spring.ai.model.embedding=transformers`，保留本地 384 维 embedding 和 `app.models` 业务路由。
- 多模型路由符合最新决策：`ModelRegistry` 保留当前实际启用的 `deepseek`、`openai`、`google-genai`，并只保留 Spring AI 2.0.0 正式版已确认适配的 `anthropic`、`ollama`、`mistral`、`bedrock` 映射；未确认适配的历史 provider 未进入可路由表。
- 三种问答模式符合 spec：auto 保留 ChatMemory + RAG + tools；data 保留 ChatMemory + tools 且不注入 RAG；knowledge 使用 no-tools ChatClient，并通过 `ChatClientAttributes.TOOL_CALLING_ADVISOR_AUTO_REGISTER` 禁用工具 advisor，同时保留 ChatMemory + RAG。
- ChatMemory 一致性符合 spec：调用处使用 `ChatMemory.CONVERSATION_ID` 传递 conversation id；自定义 repository 去除尾部当前 `UserMessage` 的策略未改。
- OpenSpec 归档一致：`openspec archive upgrade-spring-ai-2 --yes` 已创建主 spec 并归档 change；按用户要求移除了归档 `tasks.md` 中依赖真实外部环境的手工回归任务，当前任务清单无未完成项。
- 发布文档一致：本次增量只修改 README、README_EN 和 docker-compose 中的镜像 tag，`rg` 结果显示三处文件均引用 `ly753/spring-ai-rag-demo:2.0.0`，未残留应用镜像 `latest` 示例。

## 非功能审查

- 并发：本次未引入新的共享可变业务状态；`ModelRegistry` 和 `ErpAssistantService` 的 provider client 缓存继续使用 `ConcurrentHashMap`。未发现新增竞态或幂等边界变化。
- 安全：未新增 API Key 日志、鉴权绕过或 SQL 拼接路径；ERP tools 仍通过 `BaseTool` 访问 ERP SQL。knowledge 模式新增 no-tools 路径降低误调用 ERP tools 的风险。
- 边界：`getChatModel(modelId)` 在 provider 未映射或 bean 不存在时返回 null，调用方已有默认模型兜底路径；`getClient(modelId)` 对未注册 provider 会回退默认客户端。该行为符合当前设计。
- 性能：未新增数据库查询、循环扫描或阻塞 I/O；主要变化是 ChatClient 构建缓存和 advisor 参数适配，未发现新增性能热点。
- 回归风险：接口形态已通过 `ChatControllerTest` 补充自动化覆盖；本地启动和基础接口访问已验证。本次增量未改动 Java 源码和配置语义；发布文档与 Docker Hub 远端 tag 状态已通过本地扫描和 Docker Hub tag API 复核。

## 测试缺口

- 单元测试缺口：本次增量未修改 Java 源码，未新增代码级测试缺口。
- 接口测试缺口：无阻塞缺口，`ChatControllerTest` 和本地 curl 已覆盖本次提交需要证明的接口形态与基础访问。
- 发布验证缺口：无阻塞缺口。Docker CLI manifest inspect 在当前网络下仍超时，但 Docker Hub tag API 已提供远端 tag active、更新时间、镜像 digest 和 last_pushed 证据。

## 结论

- 结果：通过
- 摘要：代码、OpenSpec 主 spec 与归档材料对当前 Spring AI 2.0.0 迁移目标一致；本次镜像 `2.0.0` 发布和 README/docker-compose 引用同步未改变业务逻辑，自动化验证、本地基础启动验证、本地镜像检查和 Docker Hub tag API 远端复核均具备证据。
