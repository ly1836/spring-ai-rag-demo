涉及业务域：chat / tool / conversation / config / vo

## Why

当前 ERP Tool 全部由 `com.example.rag.tool` 包下的 `@Tool` Bean 在应用启动时固定注册，新增或调整 Tool 必须改代码、重启应用，无法通过管理页面动态维护。与此同时，对话消息表虽已预留 `tool_calls` 与 `tool_calls_count` 字段，但当前真实 Tool 命中链路没有落库明细，不利于排查 LLM 调用链路和租户隔离问题。

## What Changes

- 新增全局 LLM Tool 配置表，支持在数据库中维护 SQL 查询类 Tool 的名称、描述、输入 schema、SQL 模板、主表别名、返回行数限制和状态等信息。
- 应用启动时加载启用状态的数据库 Tool，并与现有 `com.example.rag.tool` 代码 Tool 兼容共存。
- 管理端新增「工具管理」页面入口，放在顶部「计费管理」按钮右侧，支持 Tool 配置增删改查。
- Tool 配置发生新增、修改、删除或状态变更后，服务端刷新 Tool 注册快照，并让后续 LLM 问答使用最新 Tool。
- 数据库 Tool 定义为全局配置，不按租户维护；但每次 Tool 回调执行时必须读取当前 `TenantContext` 的 `ent_code`，并在 SQL 查询结果中强制执行租户隔离。
- 新增 Tool 命中记录表，记录每次 LLM Tool 调用的租户、用户、会话、模型、工具名、入参、执行状态、耗时、错误和结果规模。
- 补齐助手消息保存时的 `tool_calls` 与 `tool_calls_count` 聚合字段，方便会话级追踪。
- 动态 Tool 模块 JSON 解析与序列化统一使用 `fastjson2`，不再新增 Jackson 2 `ObjectMapper` 兼容 Bean。
- 工具管理页状态和 Tool 命中流水来源面向用户展示中文文案，但前后端传输值保持英文枚举。
- 入参 Schema 编辑区补充 JSON Schema 说明、参数命名约束和可编辑示例数据，降低动态 Tool 配置误填概率。
- 助手消息恢复 Markdown 渲染能力，确保 LLM 基于 Tool 结果输出的表格、列表和代码块能在前端正常展示。
- 初始化演示 Tool 增加库存批次库位查询场景 `query_inventory_lot_location`，避免与现有代码 `@Tool` 示例能力重叠。
- 首期仅支持只读 SQL 查询类数据库 Tool，不支持 HTTP、MCP、脚本执行或写入型 SQL。
- 不移除、不重命名现有 `com.example.rag.tool` 代码 Tool，对外问答入口和三种问答模式保持兼容。

## Capabilities

### New Capabilities

- `dynamic-llm-tools`: 覆盖数据库定义 Tool 的全局配置管理、动态加载刷新、租户隔离执行、命中日志和前端管理页面。

### Modified Capabilities

- `spring-ai-2-runtime-compatibility`: auto/data 模式暴露的 ERP tools 从固定代码 Tool 扩展为「代码 Tool + 启用的数据库 Tool」，knowledge 模式仍不得暴露或执行任何 Tool。

## Impact

- 后端新增或调整：
  - `tool/`：数据库 Tool 配置、执行、刷新注册、ToolCallback 包装和命中日志能力。
  - `chat/ErpAssistantService`：Tool 装配从固定 `defaultTools` 调整为可刷新 ToolCallback 快照；默认 provider 和非默认 provider 的带 Tool ChatClient 缓存需要跟随 Tool 版本失效。
  - `conversation/ChatHistoryService`：保存助手消息时写入 Tool 调用聚合信息。
  - `dao/entity`、`dao/mapper`：新增 Tool 配置表和 Tool 调用日志表的 MyBatis-Plus Entity / Mapper。
  - `vo/`：新增管理端 Tool 配置和调用日志响应 record。
  - `init/ErpDatabaseInitializer` 与 `classpath:db/init/conversation-billing-schema.sql`：新增表结构和幂等演示 Tool 配置。
  - `pom.xml`：新增 `fastjson2` 依赖，供动态 Tool JSON 处理使用。
- 前端新增「工具管理」Tab，修改 `index.html`、`app.js`、`style.css`，并更新静态资源缓存版本号。
- 前端消息渲染链路调整为助手消息使用 Markdown 渲染，渲染器不可用时安全降级为 HTML 转义文本。
- API 新增 `/api/admin/tools/**` 管理接口，继续使用 `RespVO<T>`。
- 风险点：
  - 租户隔离：数据库 Tool 是全局定义，但执行结果必须按当前请求 `ent_code` 隔离，禁止跨租户泄漏。
  - SQL 安全：首期仅允许只读单层查询，动态参数必须绑定，主表别名必须是安全 SQL 标识符，禁止拼接用户输入、写入 SQL、多语句和复杂查询块。
  - 前端展示一致性：状态、来源等字段需要中文展示但保持英文传值，避免破坏后端枚举和已保存数据。
  - Markdown 渲染安全：助手消息需要支持表格展示，同时在渲染器不可用或解析失败时避免未转义文本进入 HTML。
  - LLM Provider 切换：不同 provider 的 ChatClient 都必须使用同一份最新 ToolCallback 快照。
  - 计费扣费：问答前后置计费流程保持不变，Tool 命中日志不得影响扣费成功与否。
