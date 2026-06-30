## 1. Docker 构建入口

- [x] 1.1 检查现有 `deploy/Dockerfile` 与根目录 `Dockerfile` 的构建指令，删除不再使用的 `deploy/Dockerfile`，保留构建仍依赖的 `deploy/settings.xml`。
- [x] 1.2 确保 Dockerfile 使用 Java 17 Maven 多阶段构建，运行阶段只复制 Spring Boot jar 并暴露 `8080`。
- [x] 1.3 检查 `.dockerignore`，确认不会排除 `pom.xml`、`src/main/resources/static`、`src/main/resources/models`、`src/main/resources/application.yml` 和 `deploy/settings.xml` 等构建必需文件。
- [x] 1.4 确认 Dockerfile 和镜像配置不写入真实 `DEEPSEEK_API_KEY`、`DASHSCOPE_API_KEY`、`GOOGLE_GENAI_API_KEY`。

## 2. Docker Compose 编排

- [x] 2.1 更新 `docker-compose.yml`，新增 `app` 服务并使用 README 中的远程 Spring Boot 应用镜像。
- [x] 2.2 为 `app` 服务映射 `8080:8080`，并通过环境变量覆盖 PgVector 和 MySQL 数据源连接地址。
- [x] 2.3 统一 MySQL `MYSQL_ROOT_PASSWORD` 与应用 `SPRING_DATASOURCE_ERP_PASSWORD` 的变量来源。
- [x] 2.4 为 `pgvector` 增加 `pg_isready` 健康检查，为 `mysql` 增加 `mysqladmin ping` 健康检查。
- [x] 2.5 配置 `app.depends_on` 等待 `pgvector` 和 `mysql` 达到健康状态后再启动。
- [x] 2.6 移除或停用 MySQL entrypoint 对 `./sql` 的裸 SQL 自动导入，避免与应用幂等初始化重复执行。

## 3. MySQL 表结构初始化

- [x] 3.1 在 `com.example.rag.init` 下新增启动初始化组件，使用构造器注入 `@Qualifier("erpJdbcTemplate") JdbcTemplate`。
- [x] 3.2 参考 `classpath:db/init/business-data.sql` 实现 `b_*` ERP 业务表的 `CREATE TABLE IF NOT EXISTS` 初始化。
- [x] 3.3 参考 `classpath:db/init/conversation-billing-schema.sql` 实现 `a_*` 租户、对话、计费、用量表的 `CREATE TABLE IF NOT EXISTS` 初始化。
- [x] 3.4 确保初始化只访问 ERP MySQL 数据源，不通过 `@Primary` PgVector 数据源执行 MySQL DDL。
- [x] 3.5 为初始化组件增加必要中文类注释和方法注释，说明来源 SQL 文件、幂等策略和错误处理边界。
- [x] 3.6 将初始化 SQL 作为 `src/main/resources/db/init/` 下的唯一 classpath 资源来源，确认与项目顶层 `sql/` 内容一致后删除顶层重复副本。

## 4. MySQL 演示数据初始化

- [x] 4.1 为租户、用户、对话、消息、Token 用量、套餐、计价规则、计费账户、交易流水和账单定义幂等存在性判断。
- [x] 4.2 为销售、采购、生产、仓库、质检、售后、财务、委外等 ERP 演示业务表定义幂等存在性判断。
- [x] 4.3 将 `business-data.sql` 中的演示数据转换为按业务键 `exists -> insert` 的初始化逻辑。
- [x] 4.4 将 `conversation-billing-schema.sql` 中的演示数据转换为按业务键 `exists -> insert` 的初始化逻辑。
- [x] 4.5 确保业务键已存在时跳过插入，不清空表、不覆盖用户已有字段值。
- [x] 4.6 对 `a_billing_price_rule` 使用 `model + effective_date` 判断是否已存在，避免重复计价规则。

## 5. 配置与文档

- [x] 5.1 如需调整 `application.yml`，保留本机开发默认值，并确保 Compose 环境变量可覆盖容器内数据源。
- [x] 5.2 如 README 中仍描述手工 `docker run` 和手工导入 SQL，更新 Docker 启动说明为 `docker-compose up`，并补充当前镜像本地构建和推送命令。
- [x] 5.3 记录启动前需要通过环境变量提供真实 LLM API Key；compose 文件不提供真实 Key 默认值。
- [x] 5.4 更新非归档 OpenSpec 与 README 中的 SQL 路径引用，避免继续指向已删除的顶层 `sql/`。
- [x] 5.5 为 `.gitignore` 增加 `docs/logs` 例外，确保 `test_report.log`、`self_review.md`、`chat_history.md` 可提交。
- [x] 5.6 生成 `docs/logs/20260630-containerized-runtime-init/` 下的测试报告、自查报告和 AI 交互记录。
- [x] 5.7 重新梳理 README 启动说明，区分中间件单独启动、单独 Docker 启动应用、docker-compose 一键启动，并为 docker-compose 补充命令行环境变量启动示例与 `.env` 可选配置示例。

## 6. 验证

- [x] 6.1 执行 Maven 编译或测试，确认新增初始化代码可编译。
- [x] 6.2 执行 `docker compose config`，确认 Compose 配置可解析且 app、pgvector、mysql 服务定义正确。
- [x] 6.3 执行 `mvn package -DskipTests`，确认 Dockerfile 构建阶段所需的 Maven 打包命令可成功完成。
- [x] 6.4 检查打包产物，确认初始化 SQL、静态资源和本地嵌入模型已进入 Spring Boot jar。
- [x] 6.5 执行 `openspec validate --all --strict`，确认当前 change 与主 spec 严格校验通过。
- [x] 6.6 扫描非归档范围，确认无 `sql/business-data.sql` 或 `sql/conversation-billing-schema.sql` 残留引用。
- [x] 6.7 执行 `git diff --check`，确认无空白错误；CRLF 提示不作为阻断项。
