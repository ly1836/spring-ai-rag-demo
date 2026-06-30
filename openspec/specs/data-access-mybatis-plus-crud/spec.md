# data-access-mybatis-plus-crud 规格

## Purpose

本规格定义 ERP MySQL 系统表接入 MyBatis-Plus 后的数据访问、租户隔离、受控 CRUD、计费与对话链路兼容性要求，确保租户、用户、对话记录、Token 用量、套餐、计价规则、计费账户、交易流水和账单等结构化数据可维护且不破坏现有智能问答能力。

## Requirements

### Requirement: ERP 系统表 MyBatis-Plus 数据访问
系统 SHALL 为 `classpath:db/init/conversation-billing-schema.sql` 中的租户、用户、对话、消息、Token 用量、套餐、计价规则、计费账户、交易流水和账单表提供 MyBatis-Plus Entity 与 Mapper，用于结构化增删改查和受控业务写入。

#### Scenario: 系统表 Mapper 使用 ERP 数据源
- **WHEN** 应用启动并加载 MyBatis-Plus Mapper
- **THEN** 所有系统表 Mapper MUST 绑定 ERP MySQL 数据源
- **AND** PgVector / Spring AI 默认数据源 MUST 保持 `@Primary` 行为不变

#### Scenario: 现有功能仍可调用
- **WHEN** 用户调用 `/api/ask`、`/api/billing/*` 或 `/api/conversations/*`
- **THEN** 系统 MUST 返回与迁移前兼容的响应结构和业务语义
- **AND** Controller 对外路径与 `RespVO<T>` 包装规则 MUST 保持不变

### Requirement: 租户隔离插件
系统 SHALL 通过 MyBatis-Plus 租户插件对租户隔离表自动追加 `ent_code` 条件，租户值 MUST 来自 `TenantContext.requireEntCode()`。

#### Scenario: 查询租户隔离表
- **WHEN** 当前请求上下文中存在租户 `ENT001` 且 Mapper 查询租户隔离表
- **THEN** SQL 执行时 MUST 自动限制为 `ent_code = 'ENT001'`
- **AND** Service 和 Mapper 方法参数 MUST NOT 要求调用方传入 `ent_code`

#### Scenario: 跨租户访问被隔离
- **WHEN** 当前租户尝试通过会话 ID、流水号或账单号查询其他租户数据
- **THEN** 系统 MUST 返回空结果或业务不可访问错误
- **AND** 响应 MUST NOT 泄露其他租户的数据内容

#### Scenario: 插入租户隔离表
- **WHEN** 系统向租户隔离表新增记录
- **THEN** 新记录 MUST 自动写入当前 `TenantContext` 中的 `ent_code`
- **AND** Controller 请求体 MUST NOT 接收或信任客户端传入的 `ent_code`

### Requirement: 可配置忽略租户隔离表
系统 SHALL 在 `application.yml` 中提供可配置的忽略租户隔离表名列表，并支持配置多张表。

#### Scenario: 全局套餐表忽略租户隔离
- **WHEN** `a_billing_plan` 配置在忽略表列表中
- **THEN** 查询套餐列表时 MUST NOT 自动追加 `ent_code` 条件
- **AND** 所有租户 MUST 能看到相同的生效套餐配置

#### Scenario: 全局计价规则表忽略租户隔离
- **WHEN** `a_billing_price_rule` 配置在忽略表列表中
- **THEN** 计费扣费查询模型计价规则时 MUST NOT 自动追加 `ent_code` 条件
- **AND** 未找到计价规则时 MUST 保持现有按零计费且不中断回答的行为

#### Scenario: 多表配置大小写兼容
- **WHEN** 配置中包含多张忽略表且表名大小写与 SQL 中不完全一致
- **THEN** 系统 MUST 以大小写不敏感方式判断是否忽略租户隔离

### Requirement: 受控 CRUD 与审计保护
系统 SHALL 按表的数据性质区分普通 CRUD 与受控写入能力，避免破坏对话历史、账务流水和用量审计。

#### Scenario: 配置类表支持普通维护
- **WHEN** 管理端维护套餐或计价规则
- **THEN** 系统 MAY 通过 MyBatis-Plus 提供新增、修改、查询和状态变更能力
- **AND** 操作结果 MUST 通过 `RespVO<T>` 返回

#### Scenario: 交易流水不可普通物理删除
- **WHEN** 调用方尝试删除计费交易流水
- **THEN** 系统 MUST NOT 提供普通物理删除能力
- **AND** 充值、扣费、退款等流水 MUST 通过受控业务方法追加

#### Scenario: 对话记录使用软删除
- **WHEN** 用户删除会话
- **THEN** 系统 MUST 将会话状态更新为 `deleted`
- **AND** 系统 MUST NOT 物理删除会话和消息记录

#### Scenario: Token 用量聚合受控更新
- **WHEN** LLM 调用完成并产生 token 用量
- **THEN** 系统 MUST 通过受控 Mapper SQL 或 Service 方法更新每日/月度用量
- **AND** 聚合更新 MUST 保持现有累加语义

### Requirement: 计费与对话现有链路兼容
系统 SHALL 在迁移内部数据访问后保持现有对话保存、ChatMemory 加载、配额检查、充值、扣费、交易流水和用量统计行为兼容。

#### Scenario: 对话保存与历史查询兼容
- **WHEN** 用户完成一次非流式或流式问答
- **THEN** 系统 MUST 保存用户消息和助手消息
- **AND** 历史记录接口 MUST 能按当前租户和用户查询会话与消息

#### Scenario: 扣费流程兼容
- **WHEN** LLM 调用返回 token 用量
- **THEN** 系统 MUST 按现有公式计算费用并扣减账户
- **AND** 系统 MUST 插入交易流水并更新每日用量聚合

#### Scenario: 充值流程兼容
- **WHEN** 用户通过现有充值接口提交有效充值金额
- **THEN** 系统 MUST 增加账户余额、激活账户状态并插入充值流水
- **AND** 响应 MUST 包含交易流水号、充值金额和充值后余额
