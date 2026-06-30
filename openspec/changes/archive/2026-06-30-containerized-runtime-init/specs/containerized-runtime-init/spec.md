## ADDED Requirements

### Requirement: Docker Compose 启动完整运行时

系统 SHALL 提供 Docker Compose 编排，使开发者在项目根目录执行 `docker-compose up` 后启动应用、PostgreSQL/PgVector 和 MySQL，并能通过宿主机访问应用页面。

#### Scenario: 首次执行 compose 启动应用

- **WHEN** 开发者在项目根目录执行 `docker-compose up`
- **THEN** Compose MUST 使用 `ly753/spring-ai-rag-demo:latest` 远程镜像启动 Spring Boot 应用容器
- **AND** MUST 启动 PostgreSQL/PgVector 容器和 MySQL 容器
- **AND** 宿主机访问 `http://localhost:8080` MUST 返回项目静态首页

#### Scenario: 应用容器连接 compose 中间件

- **WHEN** 应用运行在 Compose 网络内
- **THEN** PostgreSQL/PgVector 数据源 MUST 连接 `pgvector:5432`
- **AND** MySQL ERP 数据源 MUST 连接 `mysql:3306`
- **AND** 应用容器 MUST NOT 使用 `localhost` 连接数据库容器

#### Scenario: 中间件健康后启动应用

- **WHEN** MySQL 或 PgVector 尚未通过健康检查
- **THEN** 应用服务 MUST 等待依赖服务健康后再启动
- **AND** 数据库尚未就绪时 MUST NOT 因抢先连接导致应用容器永久失败

### Requirement: 应用镜像可重复构建

系统 SHALL 提供 Dockerfile 构建 Spring Boot 可运行镜像，镜像内必须包含运行项目所需的后端代码、静态资源、本地嵌入模型和配置文件。

#### Scenario: Dockerfile 构建 jar 并运行

- **WHEN** Docker 构建应用镜像
- **THEN** 构建阶段 MUST 使用 Maven 生成 Spring Boot jar
- **AND** 运行阶段 MUST 使用 Java 17 运行该 jar
- **AND** 镜像入口 MUST 启动 `RagDemoApplication`

#### Scenario: 镜像包含本地模型资源

- **WHEN** 应用容器启动并初始化嵌入模型
- **THEN** `classpath:models/embedding/model.onnx` MUST 可被加载
- **AND** `classpath:models/embedding/tokenizer.json` MUST 可被加载

#### Scenario: 镜像包含初始化 SQL 资源

- **WHEN** Spring Boot jar 或应用镜像完成打包
- **THEN** `classpath:db/init/business-data.sql` MUST 可被加载
- **AND** `classpath:db/init/conversation-billing-schema.sql` MUST 可被加载
- **AND** 应用初始化 MUST NOT 依赖项目顶层 `sql/` 目录

#### Scenario: LLM Key 不写入镜像

- **WHEN** 构建或查看应用镜像配置
- **THEN** 镜像和 Dockerfile MUST NOT 固化真实 `DEEPSEEK_API_KEY`、`DASHSCOPE_API_KEY` 或 `GOOGLE_GENAI_API_KEY`
- **AND** 运行时 MUST 通过环境变量注入这些 Key

### Requirement: MySQL 表结构自动初始化

系统 SHALL 在应用启动时针对 ERP MySQL 数据源自动创建项目所需的业务表、租户表、对话表、计费表和用量统计表。

#### Scenario: MySQL 空库启动

- **WHEN** MySQL 中存在 `erp` 数据库但缺少项目业务表
- **THEN** 应用启动初始化 MUST 创建 `classpath:db/init/business-data.sql` 中定义的 `b_*` 业务表
- **AND** MUST 创建 `classpath:db/init/conversation-billing-schema.sql` 中定义的 `a_*` 系统表
- **AND** 项目顶层 `sql/` 中的重复脚本 MUST NOT 作为运行时初始化来源

#### Scenario: 表结构初始化可重复执行

- **WHEN** 应用第二次或多次启动且表已经存在
- **THEN** 初始化 MUST NOT 因表已存在而失败
- **AND** 已存在表中的数据 MUST NOT 被清空或覆盖

#### Scenario: 初始化只访问 ERP MySQL

- **WHEN** 应用执行业务表和计费表初始化
- **THEN** 初始化 MUST 使用 `erpJdbcTemplate` 或等价的 ERP MySQL 数据源访问
- **AND** MUST NOT 在 PgVector 主数据源中创建 MySQL 业务表

### Requirement: MySQL 演示数据幂等初始化

系统 SHALL 在应用启动时补齐演示数据，并在每次插入前判断目标数据是否已经存在。

#### Scenario: 空表插入演示数据

- **WHEN** MySQL 表结构存在但演示数据不存在
- **THEN** 初始化 MUST 插入租户、用户、ERP 业务、对话历史、套餐、计价规则、计费账户、交易流水、账单和用量统计演示数据
- **AND** 插入的数据 MUST 与 `classpath:db/init/business-data.sql` 和 `classpath:db/init/conversation-billing-schema.sql` 中的示例数据语义一致

#### Scenario: 重复启动不重复插入

- **WHEN** 应用已经完成过一次演示数据初始化并再次启动
- **THEN** 初始化 MUST 根据业务唯一键识别已有数据并跳过插入
- **AND** 订单、租户、套餐、计价规则、交易流水和对话消息等演示数据 MUST NOT 出现重复记录

#### Scenario: 已有用户数据不被覆盖

- **WHEN** 用户已经修改或新增了 MySQL 中的业务数据
- **THEN** 初始化 MUST NOT 清空表
- **AND** 初始化 MUST NOT 覆盖业务键已存在的数据字段值

#### Scenario: 计价规则按模型和生效日期判断

- **WHEN** 初始化 `a_billing_price_rule` 数据
- **THEN** 系统 MUST 以 `model + effective_date` 判断同一条计价规则是否存在
- **AND** 已存在规则 MUST 保持原值，不被启动初始化覆盖

### Requirement: 容器化运行保持现有业务行为

系统 SHALL 在 Docker Compose 运行模式下保持现有前端、问答、RAG、Tool Calling、历史记录和计费接口行为兼容。

#### Scenario: 前端静态资源可访问

- **WHEN** 用户访问 `http://localhost:8080`
- **THEN** 系统 MUST 返回现有单页应用入口
- **AND** `app.js`、`style.css` 和 `vendor/*` 静态资源 MUST 正常加载

#### Scenario: ERP Tool Calling 可查询演示业务数据

- **WHEN** 用户以 `X-Ent-Code: ENT001` 发起需要查询 ERP 数据的问答
- **THEN** Tool Calling 查询 MUST 能访问初始化后的 `b_*` 业务表
- **AND** 查询结果 MUST 继续按 `ent_code` 隔离当前租户数据

#### Scenario: 计费功能可读取初始化数据

- **WHEN** 用户访问计费账户、套餐、交易流水、每日用量或月度用量接口
- **THEN** 接口 MUST 能读取初始化后的 `a_*` 计费与用量表
- **AND** 响应结构 MUST 保持现有 `RespVO<T>` 约定

#### Scenario: RAG 向量库初始化不受影响

- **WHEN** 应用在 Compose 模式下启动
- **THEN** Spring AI PgVector schema 初始化 MUST 继续使用 PostgreSQL/PgVector 数据源
- **AND** MySQL 初始化逻辑 MUST NOT 改变 `spring.ai.vectorstore.pgvector.initialize-schema` 的现有行为
