## 1. 基础设施

- [x] 1.1 在 `pom.xml` 新增 `mybatis-plus-spring-boot3-starter` 依赖，保留 `spring-boot-starter-jdbc` 以兼容 PgVector / Spring AI。
- [x] 1.2 新增 ERP MyBatis-Plus 专用配置类，显式绑定 `erpDataSource`、`erpSqlSessionFactory`、`erpSqlSessionTemplate` 和 ERP 事务管理器。
- [x] 1.3 配置 `@MapperScan` 只扫描租户、计费、对话相关 Mapper 包，避免 MyBatis-Plus 绑定 PgVector 主数据源。
- [x] 1.4 新增 `TenantProperties` 配置绑定，支持 `app.tenant.column` 和 `app.tenant.ignore-tables`。
- [x] 1.5 在 `application.yml` 增加租户列名与忽略租户隔离表配置，默认包含 `a_billing_plan`、`a_billing_price_rule`。
- [x] 1.6 新增 MyBatis-Plus 租户插件，租户值从 `TenantContext.requireEntCode()` 获取，忽略表名按大小写不敏感匹配。
- [x] 1.7 新增公共字段自动填充能力，处理租户隔离表插入时的 `ent_code` 以及可自动维护的 `created_at`、`updated_at`。

## 2. Entity 与 Mapper

- [x] 2.1 为 `a_tenant`、`a_tenant_user` 新增租户域 Entity 和 Mapper。
- [x] 2.2 为 `a_chat_conversation`、`a_chat_message` 新增对话域 Entity 和 Mapper。
- [x] 2.3 为 `a_token_usage_daily`、`a_token_usage_monthly` 新增用量统计 Entity 和 Mapper。
- [x] 2.4 为 `a_billing_plan`、`a_billing_price_rule`、`a_billing_account`、`a_billing_transaction`、`a_billing_invoice` 新增计费域 Entity 和 Mapper。
- [x] 2.5 校准 Entity 字段类型：金额使用 `BigDecimal`，大计数字段使用 `Long`，日期/时间字段与现有 VO 转换保持兼容。
- [x] 2.6 为需要复杂 SQL 的 Mapper 增加自定义方法，包括账户套餐 JOIN、价格规则查找、交易流水分页、用量聚合查询。

## 3. 对话记录迁移

- [x] 3.1 将 `ChatHistoryService` 的会话创建、消息保存、统计更新改为调用 MyBatis-Plus Mapper，保留现有 public 方法签名。
- [x] 3.2 保留 `INSERT IGNORE` 创建会话的幂等语义，可通过自定义 Mapper SQL 实现。
- [x] 3.3 将会话列表、消息详情、会话状态校验、软删除查询改为 Mapper 调用，并确保租户与用户隔离仍由上下文控制。
- [x] 3.4 将 `JdbcChatMemoryRepository` 内部读取最近消息逻辑改为 Mapper 调用，保留剔除末尾用户消息的行为。
- [x] 3.5 验证 `/api/conversations`、`/api/conversations/{id}/messages`、删除会话、续聊校验行为不变。

## 4. 计费系统迁移

- [x] 4.1 将 `BillingService.getAccount()` 改为 Mapper 查询账户与套餐关联信息，保持 `BillingVO.AccountResponse` 不变。
- [x] 4.2 将套餐列表、交易流水、每日用量、月度用量查询改为 Mapper 调用，保持现有接口响应结构不变。
- [x] 4.3 将配额校验 `checkQuota()` 改为 Mapper 查询账户与套餐信息，未找到账户时继续按现有逻辑跳过检查并记录日志。
- [x] 4.4 将充值逻辑迁移到 ERP 事务管理器下的 Mapper 调用，保证账户更新和充值流水插入在同一事务中。
- [x] 4.5 将扣费逻辑迁移到 ERP 事务管理器下的 Mapper 调用，保留余额扣减、流水插入和每日用量 upsert 的原子业务语义。
- [x] 4.6 将模型计价规则查询改为 Mapper 调用，并验证 `a_billing_price_rule` 忽略租户隔离后仍可查到全局规则。
- [x] 4.7 验证 `/api/billing/account`、`/api/billing/plans`、`/api/billing/transactions`、`/api/billing/recharge`、日/月用量接口行为不变。

## 5. 受控 CRUD 与接口扩展

- [x] 5.1 根据是否需要管理端能力，新增租户、租户用户、套餐、计价规则、账单的受控 CRUD Service。
- [x] 5.2 新增管理型 Controller 时，统一返回 `RespVO<T>`，业务异常直接抛出 `IllegalArgumentException` 或 `IllegalStateException`。
- [x] 5.3 对交易流水、消息历史、Token 用量表只开放查询或受控状态更新，不提供普通物理删除接口。
- [x] 5.4 若新增前端管理页面，同步更新 `static/app.js` / `style.css` / `index.html`，所有非流式 API 走 `apiCall` / `apiPost`，并更新资源版本号。

## 6. 验证

- [x] 6.1 新增租户插件测试：租户隔离表自动追加 `ent_code`，忽略表不追加，忽略表名大小写不敏感。
- [x] 6.2 新增跨租户访问测试：不同租户不能查询到对方会话、消息、账单、流水、用量数据。
- [x] 6.3 新增现有功能回归测试：问答保存、历史读取、ChatMemory 加载、充值、扣费、用量统计。
- [x] 6.4 验证 PgVector / RAG 搜索仍使用主数据源，MyBatis-Plus Mapper 只访问 ERP MySQL。
- [x] 6.5 执行 `mvn test` 或至少执行相关单元/集成测试，记录无法执行的外部依赖原因。
- [x] 6.6 执行 `openspec status --change data-access-mybatis-plus-crud`，确认 change 达到 apply-ready 状态。
