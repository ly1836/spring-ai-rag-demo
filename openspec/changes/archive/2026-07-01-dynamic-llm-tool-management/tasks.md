## 1. 表结构与初始化

- [x] 1.1 在 `conversation-billing-schema.sql` 新增 `a_llm_tool` 与 `a_tool_call_log` DDL，字段、索引和注释与 design 保持一致，并为调用日志补充 `(ent_code, created_at)` 查询索引。
- [x] 1.2 在初始化 SQL 中加入 1-2 条只读 SQL 查询类演示 Tool，覆盖 `tool_name`、`description`、`input_schema`、`sql_template`、`status` 等核心字段。
- [x] 1.3 更新 `ErpDatabaseInitializer` 的种子数据幂等键，新增 `a_llm_tool` 按 `tool_name` 判断，确认重复启动不会覆盖已有 Tool。
- [x] 1.4 更新 `TenantProperties` 默认忽略表配置和 `application.yml`，将全局配置表 `a_llm_tool` 加入 ignore；确认 `a_tool_call_log` 保持租户隔离。

## 2. 数据访问与 VO

- [x] 2.1 新增 `LlmToolEntity`、`ToolCallLogEntity`，字段类型与 MySQL 表结构一致，使用 MyBatis-Plus ERP 数据源。
- [x] 2.2 新增 `LlmToolMapper`、`ToolCallLogMapper`，提供基础 CRUD、启用 Tool 列表查询和按当前租户分页查询调用日志。
- [x] 2.3 在 `AdminVO` 中新增 `ToolItem`、`ToolCallLogItem`、`ToolRefreshResult` record，补齐字段注释与请求/响应语义。
- [x] 2.4 增加必要的单元测试或 Mapper 级验证，覆盖 `a_llm_tool` 忽略租户隔离、`a_tool_call_log` 按当前 `ent_code` 查询。

## 3. 动态 Tool 执行器

- [x] 3.1 新增数据库 Tool SQL 校验组件，拒绝空 SQL、非单层 `SELECT` 查询、写入语句、DDL、多语句、多查询块和危险关键字。
- [x] 3.2 新增命名参数解析与绑定组件，将 SQL 模板中的参数转换为 `?` 占位，并只绑定 `input_schema` 声明的参数。
- [x] 3.3 新增租户条件注入逻辑，支持默认 `ent_code = ?` 和可选主表别名场景，插入位置需兼容 `ORDER BY`、`GROUP BY`、`LIMIT`。
- [x] 3.4 新增数据库 Tool 执行器，基于 `erpJdbcTemplate` 执行只读查询，强制追加当前 `TenantContext.requireEntCode()`，并限制返回行数。
- [x] 3.5 为 SQL 校验、参数绑定、租户注入、缺失租户上下文、主表别名校验和返回行数限制补充单元测试。
- [x] 3.6 动态 Tool 模块 JSON 解析与序列化统一使用 `fastjson2`，移除新增 Jackson 2 `ObjectMapper` 兼容 Bean 依赖。

## 4. ToolCallback 注册与刷新

- [x] 4.1 新增 `ToolRegistryService`，启动时合并现有代码 Tool 和启用数据库 Tool，生成不可变 `ToolSnapshot`。
- [x] 4.2 使用 `MethodToolCallbackProvider` 将现有 `BaseTool` Bean 转换为代码 ToolCallback，确保现有 `@Tool` 描述和入参保持兼容。
- [x] 4.3 新增 `DatabaseToolCallbackFactory`，根据 `a_llm_tool` 定义生成 Spring AI `ToolCallback`。
- [x] 4.4 新增 `LoggingToolCallback` 或等价包装器，统一包裹代码 Tool 和数据库 Tool，记录调用耗时、状态、入参和结果规模。
- [x] 4.5 在管理端保存、删除、禁用和手动刷新后调用 `ToolRegistryService.refresh()`，刷新失败时返回明确业务错误且不污染当前可用快照。
- [x] 4.6 调整 `ErpAssistantService` 带 Tool ChatClient 构建逻辑，用 `provider + toolVersion` 缓存，替代固定 `baseChatClient` 的旧 Tool 列表。
- [x] 4.7 验证 DeepSeek、OpenAI 兼容和 Google GenAI provider 均使用当前最新 ToolSnapshot，knowledge 模式仍使用无 Tool ChatClient。

## 5. Tool 命中日志与对话聚合

- [x] 5.1 新增 `ToolCallRecorder`，在一次问答上下文中收集本轮 Tool 调用摘要，并兼容非流式和流式链路。
- [x] 5.2 新增 `ToolCallLogService`，负责追加 `a_tool_call_log`，日志写入失败只记录 warn，不阻断 Tool 返回。
- [x] 5.3 调整非流式 `recordAndReturn()`，保存助手消息时写入 `tool_calls` JSON 和 `tool_calls_count`。
- [x] 5.4 调整流式 `streamWithRecording()`，在 Reactor 上下文恢复后写入本轮 Tool 聚合字段，取消和错误路径也保持可追踪。
- [x] 5.5 验证 knowledge 模式不暴露 Tool、不执行 Tool、不新增 Tool 调用日志。

## 6. 管理端 API

- [x] 6.1 新增 `ToolManagementService`，提供 Tool 列表、新增、更新、删除、启停、手动刷新和调用日志查询能力。
- [x] 6.2 新增 `ToolManagementController`，路径 `/api/admin/tools`，所有非流式响应使用 `RespVO<T>`。
- [x] 6.3 对保存接口补齐参数校验：名称合法性、名称唯一性、JSON Schema 可解析、SQL 只读、主表别名合法、resultLimit 合法、更新必须带 ID。
- [x] 6.4 对调用日志查询接口按当前租户隔离，支持 `page`、`size`、`toolName` 可选过滤。
- [x] 6.5 增加 Controller/Service 测试，覆盖成功 CRUD、非法 SQL、重复名称、刷新失败和日志查询租户隔离。

## 7. 前端工具管理页面

- [x] 7.1 在 `index.html` 顶部「计费管理」按钮右侧新增「工具管理」按钮和对应 tab 面板。
- [x] 7.2 在 `app.js` 增加工具管理交互：列表加载、表单编辑、新增、更新、删除、状态维护、手动刷新和调用日志加载，全部通过 `apiCall` / `apiPost`。
- [x] 7.3 在 `style.css` 增加工具管理列表、表单、SQL/JSON 编辑区和日志表样式，复用现有主题变量。
- [x] 7.4 所有动态渲染字段进入 `innerHTML` 前使用 `escapeHtml()`，删除等破坏性操作使用现有确认风格。
- [x] 7.5 修改 `index.html` 中 `app.js` / `style.css` 的缓存版本号，确保浏览器加载最新前端资源。

## 8. 验证

- [x] 8.1 执行 `openspec validate dynamic-llm-tool-management --strict`，确保 proposal、design、specs、tasks 全部有效。
- [x] 8.2 执行 Maven 单元测试，至少覆盖新增 SQL 执行器、ToolRegistry、管理 Service 和对话聚合路径。
- [x] 8.3 检查 `src/main/java` 与 `src/test/java` 中无新增 Jackson 2 `ObjectMapper` / `JsonNode` / `TypeReference` 残留。
- [x] 8.4 执行 `git diff --check`，确认无空白格式错误。
