# AI 交互记录

## 元数据

- 变更 ID：upgrade-spring-ai-2
- 最近更新：2026-06-30 16:35:08
- 开发者：leiyang
- AI工具：Codex

## 记录更新

- 2026-06-30 15:37:50：增量整合 Spring AI 2.x 升级 change 的需求澄清、OpenSpec 生成与实现、provider 映射口径调整、review 结论和本地验证结果。当前报告对应整个 `upgrade-spring-ai-2` change。
- 2026-06-30 15:45:37：用户要求解决 review 报告中的风险点。AI 确认接口形态缺少自动化证据，补充 `ChatControllerTest` 覆盖模型列表、非流式问答、流式 SSE 和文档搜索接口形态，并更新自查和验证报告。
- 2026-06-30 16:35:08：用户要求归档 `upgrade-spring-ai-2` 后继续完成未完成 task，并说明如果是外部测试就去掉。AI 通过 `openspec archive upgrade-spring-ai-2 --yes` 同步主 spec 并归档 change，随后从归档 `tasks.md` 移除依赖真实模型 API、浏览器端到端操作和外部运行环境的手工回归项，重新执行提交门禁验证并更新日志。

## 关键提示词

- 用户先询问当前项目是 Spring AI 1.0 还是 2.0，以及两者版本区别，目标模块为 `spring-ai/spring-ai-rag/`。
- 用户要求将当前项目升级到 Spring AI 2.x，并梳理需要升级的配置项和代码调整点。
- 用户确认升级决策：版本组合按建议；保留并适配原功能；不修改原功能前提下清理；工具调用、ChatMemory、PgVector、MyBatis-Plus、Jackson、业务行为均与原功能保持一致。
- 用户要求生成 OpenSpec change `upgrade-spring-ai-2`，随后要求按 OpenSpec apply change 实现，且补充编码约束：最小改动、不全局格式化、新增代码加注释、新增方法加访问修饰符并放类末尾、注入位置遵循原风格、中文注释和日志、UTF-8。
- 用户要求 review 当前模块 git 变更，重点检查新加 Spring bean 注入是否存在循环依赖，以及变更是否改变原业务逻辑。
- 用户进一步澄清 provider 范围：不是“覆盖 Spring AI 1.1.4 所有官方 Chat Model starter”，需要保留和更新 2.x 对应 Model starter；随后要求确认 `PROVIDER_BEAN_NAMES` 内映射在 Spring AI 2.x 是否适配，并最终要求按“Spring AI 2.0.0 正式版已确认适配”口径更新。
- 用户要求运行 `test-report`、`self-review`、`chat-history` 三个技能，生成本地验证、自查和 AI 交互记录。
- 用户要求解决 review 报告中的风险点，推动补充接口形态自动化测试并更新日志结论。
- 用户要求执行 OpenSpec 归档，并在提交前完成未完成任务；对外部测试类任务明确要求移除。

## 重要 AI 建议

- 建议升级依赖时保持 `ModelRegistry + app.models` 多模型路由，不改成单一 `spring.ai.model.chat` 默认模型模式，以免破坏前端模型下拉和请求级模型名覆盖。
- 建议 knowledge 模式不要全局关闭 tool calling，而是构建不带默认 tools 的 ChatClient，并在局部 prompt 调用中禁用 tool advisor，避免影响 auto/data 模式。
- 建议 ChatMemory 继续使用项目自定义 `JdbcChatMemoryRepository`，通过 `ChatMemory.CONVERSATION_ID` 适配 Spring AI 2.x advisor 参数，不迁移到官方默认 JDBC schema。
- 建议 provider 映射不要笼统恢复所有 Spring AI 1.1.4 历史 starter；应按当前锁定的 Spring AI 2.0.0 正式版核对 starter 坐标和 AutoConfiguration bean 名称，只保留确认适配的映射。
- 建议保留 DeepSeek、OpenAI 兼容接口、Google GenAI 当前业务 provider；对 Anthropic、Ollama、Mistral、Bedrock Converse 等 2.0.0 GA 可解析并核对 bean 名称的 provider 保留映射；对 Azure OpenAI、Vertex AI Gemini、MiniMax、ZhiPuAI、HuggingFace、OCI GenAI 等未确认适配的历史 provider 不进入当前可路由表。
- Review 阶段提醒新增测试文件需要纳入版本控制，否则会丢失 Spring AI 2 迁移的关键回归覆盖；当前 `git status` 已显示这些测试文件为已添加或已修改状态。
- 为降低“接口未验证”风险，建议增加轻量级 `ChatControllerTest`，用 mock service 覆盖接口包装、路由和 SSE 返回形态。
- 归档阶段建议使用 `openspec archive upgrade-spring-ai-2 --yes` 同步主 spec 并移动 change，避免手工搬目录遗漏规范更新。

## 开发者决策

- 采用 Spring AI `2.0.0` 和 Spring Boot `4.0.7` 的升级组合，并使用 Boot 4 兼容的 MyBatis-Plus starter。
- 保留原有外部接口、auto/data/knowledge 三种模式、RAG、ChatMemory、PgVector 384 维、本地 transformer embedding、计费扣费、租户隔离和 ERP SQL 访问语义。
- `app.models` 保持前端模型列表和后端路由来源；不新增 `spring.ai.model.chat` 单模型配置。
- `ModelRegistry` 最终采用“只保留 Spring AI 2.0.0 正式版已确认适配 provider 映射”的口径，不保留未确认的历史 provider 映射。
- OpenSpec change 已归档到 `openspec/changes/archive/2026-06-30-upgrade-spring-ai-2/`，主 spec 已同步到 `openspec/specs/spring-ai-2-runtime-compatibility/spec.md`。
- 原归档任务中 6.1 至 6.7 为依赖真实模型 API、浏览器端到端操作或外部运行环境的手工回归项，已按用户要求移除，不再作为本次提交门禁。
- 接口形态验证通过自动化测试补强；本地启动、依赖容器、首页、`/api/models` 和 `/api/billing/account` 基础访问已验证。

## 已拒绝建议

- 拒绝将多模型路由改为单 provider 默认模型模式。
- 拒绝为了迁移而引入 Spring AI 1.1.4 的全部官方 Chat Model starter。
- 拒绝将 Spring AI 2.0.0 正式版未确认适配的历史 provider 写入当前可路由表。
- 拒绝重建向量表、替换 embedding 模型、调整计费公式、改造业务表或扩展业务 API。

## 已讨论风险

- Spring AI 2.x ChatClient/Advisor API 差异可能导致编译失败或运行时行为变化，需要用最小 API 迁移和测试收敛。
- provider bean 名称如果未按 2.0.0 AutoConfiguration 核对，可能导致模型路由走错或误导维护者。
- knowledge 模式如果仍使用带默认 tools 的 ChatClient，可能向 LLM 暴露 ERP tools，违反知识库模式不访问 ERP SQL 的要求。
- ChatMemory conversation id 传递方式变化可能导致历史上下文丢失或当前用户问题重复注入。
- Boot 4 / Jackson 3 可能影响 `RespVO<T>` 和 record 序列化。
- 真实外部 LLM 凭证、模型回答质量和浏览器端到端交互属于外部环境验收，不作为本次 OpenSpec 任务门禁；后续发布前如需要可单独补测。
- 原“接口形态未验证”风险已通过 `ChatControllerTest` 缓解；真实 LLM、PgVector、ERP MySQL 和浏览器 SSE 端到端回归仍需在可用环境补测。

## 最终结果

- OpenSpec change `upgrade-spring-ai-2` 已包含 proposal、design、delta spec 和 tasks，且 `openspec validate upgrade-spring-ai-2 --strict` 通过。
- `pom.xml` 升级 Spring Boot parent 到 `4.0.7`，Spring AI BOM 到 `2.0.0`，替换 Spring AI 2.x advisor artifact 和 MyBatis-Plus Boot 4 starter。
- `application.yml` 将 DeepSeek、OpenAI 兼容接口、Google GenAI 的默认模型配置迁移到 Spring AI 2.x 支持的 `chat.model` 层级，同时保留 `app.models` 业务配置。
- `ErpAssistantService` 适配 Spring AI 2.x `ChatOptions`、ChatMemory advisor 参数和 tool advisor 控制，保持 auto/data/knowledge 三模式语义；新增 no-tools 客户端路径支撑 knowledge 模式。
- `ModelRegistry` 保留当前业务 provider，并只保留 Spring AI 2.0.0 正式版已确认适配的 `anthropic`、`ollama`、`mistral`、`bedrock` 历史映射；未确认适配 provider 不进入可路由表。
- `DataSourceConfig` 仅迁移 Boot 4 的 `DataSourceProperties` import，双数据源结构保持不变。
- 新增和更新测试覆盖 provider 路由、未确认 provider 不误路由、knowledge 模式不暴露 tools、Controller 接口形态、Jackson 3 序列化兼容；`mvn test` 通过 25 个测试。
- `docs/logs/20260630-upgrade-spring-ai-2/test_report.log`、`self_review.md`、`chat_history.md` 已生成，作为当前 change 的本地验证、自查和 AI 协作审计记录。
- `openspec archive upgrade-spring-ai-2 --yes` 已完成归档并同步主 spec；归档后的 `tasks.md` 已移除外部/手工回归未完成项，当前无未完成任务残留。
- 提交门禁最新验证包括 `mvn test`、`mvn package -DskipTests`、`openspec validate --all --strict` 和 `git diff --check`，均通过。
