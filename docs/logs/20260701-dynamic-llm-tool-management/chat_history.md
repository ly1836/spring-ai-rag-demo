# AI 交互记录

## 元数据

- 变更 ID：dynamic-llm-tool-management
- 最近更新：2026-07-01 17:29:07
- 开发者：leiyang
- AI工具：Codex

## 记录更新

- 2026-07-01 16:29:00：增量整理本次 OpenSpec change 的需求澄清、实现建议、review 反馈、验证结果和最终代码到 OpenSpec 的对应关系；同步生成自查报告和本地验证报告。
- 2026-07-01 17:18:12：用户再次要求生成 `test-report`、`self-review`、`chat-history`；本轮重新执行 `mvn test`、非 Web 启动验证、OpenSpec 严格校验、diff 格式检查、Jackson 2 残留检查和 Spring AI Tool 注册 API 检查，并增量刷新三份日志。
- 2026-07-01 17:29:07：用户要求执行 `git-commit`；本轮完成 OpenSpec 归档和主 spec 同步，补充 README 中英文说明，重新执行提交前最终验证，并刷新三份日志。

## 关键提示词

- 用户要求扩展 LLM Tool 功能：新增数据库 Tool 定义表，应用启动加载动态 Tool，前端提供工具管理页面，配置变更后刷新运行期 Tool，LLM 问答使用最新 Tool。
- 用户要求记录 LLM 命中 Tool 的明细到数据库，方便链路跟踪。
- 用户明确要求 Tool 定义全局管理，但 Tool 回调结果必须带 `ent_code` 做租户隔离。
- 用户要求保留原 `com.example.rag.tool` 下的注解 Tool 方式，并将 Tool 相关类按子包整理。
- 用户要求新增 `com.example.rag.controller` 包并把现有 Controller 迁移到该包下。
- 用户要求替换动态 Tool 模块中的 `ObjectMapper`，评估并按建议使用 fastjson2 替换相关 JSON 处理，关联测试同步更新。
- 用户多次要求按 review 建议修复，包括去除已废弃 `defaultToolCallbacks`、保留原注解方式、移除外部验证、修复主表别名校验和调用日志索引。
- 用户要求将本次会话有效变动更新到对应 OpenSpec change 文档，并生成 test-report、self-review、chat-history。
- 用户再次要求按当前最新 git 变更刷新 test-report、self-review 和 chat-history。
- 用户要求将本次提案新增功能同步到 README 中英文文档。
- 用户要求归档 OpenSpec change `dynamic-llm-tool-management`，并继续执行本地 git commit。

## 重要 AI 建议

- 建议以 `ToolRegistryService` 维护代码 Tool 与数据库 Tool 的统一 `ToolSnapshot`，由 `ErpAssistantService` 按 `provider + toolVersion` 缓存 ChatClient，确保配置刷新后新请求使用最新 Tool。
- 建议将现有 `BaseTool` 子类通过 `MethodToolCallbackProvider` 转成 `ToolCallback`，外层统一包 `LoggingToolCallback`，兼容原 `@Tool` 注解方式并记录命中流水。
- 建议数据库动态 Tool 只支持单层只读 SQL，通过命名参数绑定、租户条件注入和返回行数限制降低 SQL 注入和跨租户风险。
- 建议 Tool 调用日志和助手消息聚合分离：每次 ToolCallback 立即写 `a_tool_call_log`，本轮聚合通过 `ToolCallRecorder` 按 traceId 暂存，保存助手消息时写入 `tool_calls` 与 `tool_calls_count`。
- 建议使用 Spring AI 2.x 的 `defaultTools` 注册 `ToolCallback`，避免使用已废弃并标记移除的 `defaultToolCallbacks`。
- 建议动态 Tool JSON 处理统一改为 `fastjson2`，删除新增 Jackson 2 `ObjectMapper` 兼容 Bean。
- review 过程中建议补充 `tableAlias` 格式校验，并为 `a_tool_call_log` 增加 `(ent_code, created_at)` 组合索引。
- 建议把实现后的实际字段、状态值、fastjson2、单层 SELECT 边界、组合索引和 `ToolRefreshResult` 同步回 OpenSpec 文档，避免设计文档停留在早期方案。
- 建议在归档前同步 delta spec 到主 spec；归档过程中发现新增 requirement 分类不匹配后，调整为 `ADDED Requirements` 再完成归档。
- 建议 README 中英文同时补充动态 Tool 管理、Tool 命中追踪、初始化演示 Tool 和包结构，保证用户文档与提案一致。

## 开发者决策

- 采纳全局 Tool 定义表方案：`a_llm_tool` 不含 `ent_code`，加入租户忽略表；Tool 查询结果和调用日志仍按当前 `ent_code` 隔离。
- 采纳保留原注解 Tool 方案：不移除原 `com.example.rag.tool` 代码 Tool，原 `@Tool` 描述和入参继续由 Spring AI 读取。
- 采纳动态 Tool 只读 SQL 首期范围：不支持 HTTP、MCP、脚本执行、写入 SQL、CTE、子查询或多查询块。
- 采纳 fastjson2 替换方案：动态 Tool 模块 JSON 解析和序列化不再依赖 Jackson 2 `ObjectMapper`。
- 采纳 review 修复项：修复暂存区 Jackson 配置残留，纳入新增测试；补主表别名校验；补调用日志组合索引。
- 明确去掉外部验证要求，仅保留本地测试、启动验证、OpenSpec 校验和代码审查结果。
- 采纳 OpenSpec 归档同步方案：主 spec 新增 `dynamic-llm-tools`，并更新 `spring-ai-2-runtime-compatibility`。
- 采纳 README 双语同步方案：中文 `README.md` 和英文 `README_EN.md` 均记录本次新增能力。

## 已拒绝建议

- 外部验证：用户要求去掉外部验证，最终报告不把外部系统验证作为完成条件。

## 已讨论风险

- 租户隔离风险：数据库 Tool 全局定义但查询必须注入当前 `ent_code`，缺失租户上下文时必须拒绝执行。
- SQL 安全风险：动态 SQL 来自管理端配置，必须限制为单层只读 SELECT、参数绑定、禁止多语句和危险关键字，主表别名必须校验。
- Spring AI 2 兼容风险：`defaultToolCallbacks` 已废弃并标记移除，最终改为 `defaultTools`。
- 日志链路风险：Tool 日志写入失败不能阻断问答主链路，助手消息聚合尽力而为。
- 性能风险：Tool 调用日志按租户分页查询需要组合索引；高频 Tool 调用和频繁刷新仍需压测。
- 测试缺口：真实浏览器 UI、真实 LLM Tool 命中、双租户端到端查询和压力测试未在本地验证中执行，作为后续联调/验收补充项，不影响本地提交门禁。

## 最终结果

- OpenSpec：change 已归档到 `openspec/changes/archive/2026-07-01-dynamic-llm-tool-management/`，主 spec 已新增 `dynamic-llm-tools` 并更新 `spring-ai-2-runtime-compatibility`。
- 表结构：`conversation-billing-schema.sql` 新增 `a_llm_tool` 与 `a_tool_call_log`，并为调用日志增加 `idx_ent_created_at (ent_code, created_at)`。
- 后端 Tool 管理：新增 `ToolManagementController`、`ToolManagementService`、`LlmToolEntity`、`ToolCallLogEntity`、`LlmToolMapper`、`ToolCallLogMapper`。
- 动态 Tool 执行：新增 `SqlToolValidator`、`SqlTemplateBinder`、`TenantSqlInjector`、`DatabaseToolExecutor`、`DatabaseToolCallbackFactory`、`DatabaseToolCallback`，实现参数绑定、只读校验、别名校验、租户条件注入和结果限制。
- Tool 注册和追踪：新增 `ToolRegistryService`、`ToolSnapshot`、`LoggingToolCallback`、`ToolCallRecorder`、`ToolCallLogService`、`ToolCallRecord`，并调整 `ErpAssistantService` 使用版本化 Tool 快照和 traceId 聚合。
- 前端：`index.html`、`app.js`、`style.css` 新增顶部「工具管理」入口、Tool 表单、列表、刷新和调用日志展示。
- 文档：`README.md` 和 `README_EN.md` 已补充动态 Tool 管理、Tool 命中追踪、动态 Tool 表、调用日志、租户隔离 SQL 执行、初始化示例和包结构。
- 测试：新增和更新动态 Tool、管理服务、注册服务、调用日志、前端静态契约和问答服务相关测试；当前 `mvn test` 71 个测试通过，非 Web 启动验证通过，OpenSpec 全量严格校验通过。
