## Context

项目当前是单模块 Spring Boot 应用，存在两个数据库：

- PgVector PostgreSQL：`@Primary` 数据源，供 Spring AI VectorStore、RAG 检索等默认自动配置使用。
- ERP MySQL：承载 ERP 业务表与 `sql/conversation-billing-schema.sql` 中的租户、对话、计费、用量统计等系统表。

现有系统表访问主要集中在 `BillingService`、`ChatHistoryService`、`JdbcChatMemoryRepository`，通过 `JdbcTemplate` 手写 SQL 完成查询、插入、更新、聚合和 upsert。用户希望将租户、套餐、对话记录、计费系统相关表做成 MyBatis-Plus 的增删改查，方便结构化关系维护，同时不能影响以前功能。

本变更不迁移 `tool/*Tool.java` 对 ERP 业务表的查询；ERP Tool 仍保持现有 `BaseTool` 查询方式，避免扩大 LLM Tool Calling 行为变更范围。

## Goals / Non-Goals

**Goals:**

- 为 `conversation-billing-schema.sql` 中的系统表建立 MyBatis-Plus Entity、Mapper 和受控 CRUD 能力。
- 新增 MyBatis-Plus ERP 专用配置，使 Mapper 只使用 ERP MySQL 数据源，不影响 PgVector / Spring AI 默认数据源。
- 新增 MyBatis-Plus 租户过滤器，统一从 `TenantContext.requireEntCode()` 注入 `ent_code`，业务参数不传递 `ent_code`。
- 支持在 `application.yml` 配置多张忽略租户隔离的表名。
- 保持现有 `/api/ask`、`/api/billing/*`、`/api/conversations/*` 请求路径、响应结构、错误处理和业务语义兼容。
- 对计费扣费、充值流水、Token 用量 upsert、会话软删除等关键写操作使用受控 Service 方法或自定义 Mapper SQL，避免普通 CRUD 破坏账务和历史审计。

**Non-Goals:**

- 不将 PgVector / Spring AI VectorStore 迁移到 MyBatis-Plus。
- 不迁移 `tool/*Tool.java` 的 ERP 业务表查询，不改变 LLM Tool Calling 返回结构。
- 不在本变更中引入独立前端工程或前端构建工具。
- 不暴露危险的物理删除能力给账务流水、消息历史和 Token 用量聚合表。
- 不改变计费公式、套餐含义、LLM 调用前后置扣费流程。

## Decisions

### 1. 使用 ERP 专用 MyBatis-Plus 配置

新增 `config/ErpMybatisPlusConfig`，显式绑定 `erpDataSource`，并通过 `@MapperScan` 只扫描 ERP 业务 Mapper 包，例如：

- `com.example.rag.tenant.mapper`
- `com.example.rag.billing.mapper`
- `com.example.rag.conversation.mapper`

保留现有 `DataSourceConfig` 中的 PgVector `@Primary` 数据源和必要的 `JdbcTemplate` Bean，避免 Spring AI VectorStore 自动配置误用 MySQL。

替代方案是让 MyBatis-Plus 使用默认数据源自动配置，但该方式容易绑定到 PgVector 主数据源，风险过高。

### 2. 租户隔离由 MyBatis-Plus 插件统一处理

新增 `TenantLineInnerInterceptor`，租户字段默认为 `ent_code`，租户值来自 `TenantContext.requireEntCode()`。

业务 Controller、Service、Mapper 方法不得从请求参数接收 `ent_code`，也不得要求调用方显式传递 `ent_code`。这可以降低跨租户伪造参数的风险，并与现有 `TenantFilter` / `TenantContext` 约束保持一致。

插入租户隔离表时，`ent_code` 由租户插件或 `MetaObjectHandler` 自动填充；若采用 `MetaObjectHandler`，应同时处理 `created_at`、`updated_at` 等公共字段。

### 3. 忽略租户隔离表由 YAML 配置

新增配置项，例如：

```yaml
app:
  tenant:
    column: ent_code
    ignore-tables:
      - a_billing_plan
      - a_billing_price_rule
```

配置类建议放在 `config/TenantProperties.java`，忽略表名统一转小写比较，避免 MySQL 大小写配置差异。

默认忽略：

- `a_billing_plan`：套餐是全局配置。
- `a_billing_price_rule`：模型计价规则是全局配置。

`a_tenant` 是否忽略取决于接口语义：当前租户查看自身信息时可按租户隔离；平台管理全部租户时应使用忽略租户隔离的管理型路径或后续权限设计。本变更默认不强制开放平台级全租户管理。

### 4. 系统表按风险分层开放 CRUD

表范围来自 `sql/conversation-billing-schema.sql`：

- 租户相关：`a_tenant`、`a_tenant_user`
- 对话相关：`a_chat_conversation`、`a_chat_message`
- 用量统计：`a_token_usage_daily`、`a_token_usage_monthly`
- 计费相关：`a_billing_plan`、`a_billing_price_rule`、`a_billing_account`、`a_billing_transaction`、`a_billing_invoice`

普通配置表可以提供完整 CRUD。账务流水、消息历史、用量聚合等审计型表只提供受控新增、查询、状态更新或聚合更新，不提供普通物理删除。

### 5. 现有 Service 逐步替换内部实现

第一阶段新增 Entity / Mapper / CRUD Service，不改变 Controller。

第二阶段将 `ChatHistoryService`、`JdbcChatMemoryRepository`、`BillingService` 内部查询替换为 Mapper 调用，但保留现有方法签名和 VO 返回结构。`INSERT IGNORE`、`ON DUPLICATE KEY UPDATE`、账户余额扣减、流水写入等 SQL 语义通过 Mapper 自定义方法保留。

这样可以让现有 `/api/ask`、`/api/billing/*`、`/api/conversations/*` 的行为保持稳定，并降低一次性迁移风险。

## Risks / Trade-offs

- [风险] MyBatis-Plus 误绑定 PgVector 主数据源，导致系统表查询打到 PostgreSQL。  
  [缓解] 显式定义 ERP 专用 `SqlSessionFactory`、`SqlSessionTemplate`、事务管理器和 `@MapperScan`。

- [风险] 租户插件对全局表误加 `ent_code` 条件，导致套餐和计价规则查询为空。  
  [缓解] `application.yml` 配置 `ignore-tables`，默认包含 `a_billing_plan`、`a_billing_price_rule`，并增加测试覆盖。

- [风险] 自定义 SQL 或 JOIN 查询绕过租户隔离。  
  [缓解] Mapper SQL 必须经过 MyBatis-Plus 拦截器；对复杂 SQL 增加跨租户测试；必要时在 SQL 中明确使用主表别名并验证插件生成结果。

- [风险] 计费扣费和充值迁移后事务管理器不正确，导致账户、流水、用量聚合不一致。  
  [缓解] 写操作显式使用 ERP 事务管理器；扣费、充值、upsert 保留在同一 Service 事务边界内。

- [风险] 普通 CRUD 暴露物理删除，破坏审计数据。  
  [缓解] Controller 层只开放受控能力；流水、消息、用量表不提供物理删除接口。

- [风险] Entity 字段类型与 DDL 不一致，造成金额、日期、Token 数精度丢失。  
  [缓解] 金额使用 `BigDecimal`，大计数字段使用 `Long`，日期时间字段按现有 VO 格式转换，Mapper 测试覆盖关键字段。

## Migration Plan

1. 新增 MyBatis-Plus 依赖和 ERP 专用配置，应用启动后验证 PgVector / ERP 双数据源均正常。
2. 新增租户配置类、租户拦截器、忽略表配置与公共字段自动填充。
3. 为系统表新增 Entity 和 Mapper，先通过单元/集成测试验证基础 CRUD 与租户隔离。
4. 替换对话模块内部数据访问，验证历史列表、消息详情、软删除、ChatMemory 加载。
5. 替换计费模块内部数据访问，验证账户查询、套餐列表、充值、扣费、交易流水、日/月用量。
6. 根据需要新增管理型 CRUD API，并同步前端时遵守 `apiCall` / `apiPost` 与缓存版本号规则。
7. 若迁移出现问题，可回滚 Service 内部调用到原 `JdbcTemplate` 实现；新增 Mapper 和 Entity 不影响旧链路。

## Open Questions

- `a_tenant` 是否需要在本次变更中提供平台级全租户管理 CRUD，还是仅支持当前租户查看/维护自身资料？
- 管理型 CRUD API 是否需要前端页面联动，还是先只提供后端接口和测试？
- 是否需要引入 MyBatis-Plus 代码生成器，还是手写 Entity / Mapper 以保持变更可控？
