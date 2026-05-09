## Why

当前项目中租户、用户、对话记录、计费账户、套餐、流水和用量统计等结构化系统表主要通过 `JdbcTemplate` 手写 SQL 访问，增删改查能力分散在 Service 内，不利于后续管理型接口扩展和关系结构维护。

本变更引入 MyBatis-Plus 作为 ERP MySQL 系统表的数据访问层，优先覆盖 `sql/conversation-billing-schema.sql` 中的 `a_*` 表，并通过统一租户插件保证 `ent_code` 隔离，同时保持现有问答、历史记录和计费功能行为不变。

## What Changes

- 新增 MyBatis-Plus 依赖与 ERP MySQL 专用配置，Mapper 只绑定 ERP 数据源，不影响 PgVector / Spring AI 默认数据源。
- 为租户、用户、对话、消息、套餐、计价规则、计费账户、交易流水、账单、Token 用量表建立 Entity、Mapper 和必要的 Service CRUD 能力。
- 新增 MyBatis-Plus 租户过滤器，默认对含 `ent_code` 的租户隔离表自动追加租户条件，租户值来自 `TenantContext.requireEntCode()`。
- 新增 `application.yml` 配置项，支持配置多张忽略租户隔离的表名，默认忽略全局配置表如 `a_billing_plan`、`a_billing_price_rule`。
- 逐步将 `BillingService`、`ChatHistoryService`、`JdbcChatMemoryRepository` 内部数据访问替换为 Mapper 调用，对外 API 路径、请求/响应结构和错误处理保持兼容。
- 计费扣费、充值流水、Token 用量 upsert、会话软删除等关键写操作保留自定义 Mapper SQL 或受控 Service 方法，避免破坏原子性和审计链路。
- 不迁移 `tool/*Tool.java` 对 ERP 业务表的查询，本变更不改变现有 LLM Tool Calling 查询路径。

## Capabilities

### New Capabilities
- `data-access-mybatis-plus-crud`: 覆盖 `conversation-billing-schema.sql` 相关系统表的 MyBatis-Plus CRUD、租户过滤器、忽略租户表配置和现有功能兼容性要求。

### Modified Capabilities

无。现有对话历史与计费展示接口保持行为兼容，本变更主要新增数据访问能力并替换内部实现。

## Impact

- 涉及业务域：`config`、`billing`、`conversation`、`vo`，可选涉及新增 `tenant` 包。
- 依赖变更：新增 `mybatis-plus-spring-boot3-starter`，保留 `spring-boot-starter-jdbc` 以满足 PgVector / Spring AI 与兼容需要。
- 配置变更：`application.yml` 新增 MyBatis-Plus 与租户忽略表配置。
- 数据源影响：必须保留 PgVector `@Primary` 数据源；MyBatis-Plus 只能绑定 ERP MySQL 数据源。
- API 影响：现有 `/api/ask`、`/api/billing/*`、`/api/conversations/*` 行为和响应结构不应变化；新增管理型 CRUD API 时仍返回 `RespVO<T>`。
- 安全影响：所有租户隔离表的查询、更新、删除不得显式从请求参数接收 `ent_code`，统一由 `TenantContext` 和 MyBatis-Plus 租户插件注入。
