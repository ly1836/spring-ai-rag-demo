## Context

项目当前是单模块 Spring Boot 应用，运行时依赖两个数据库：

- PostgreSQL + PgVector：`@Primary` 数据源，供 Spring AI VectorStore、RAG 检索和嵌入向量存储使用。
- MySQL：ERP 业务库 `erp`，承载 ERP 业务表、租户、对话历史、计费账户、计价规则、交易流水和用量统计。

现有 [docker-compose.yml](../../../docker-compose.yml) 只启动 `pgvector` 和 `mysql`，没有应用容器；[src/main/resources/application.yml](../../../src/main/resources/application.yml) 中的数据源默认指向本机 `localhost`，应用放进容器后会连接到容器自身而不是中间件容器。MySQL 初始化最终以 `classpath:db/init/business-data.sql` 和 `classpath:db/init/conversation-billing-schema.sql` 作为唯一资源来源；这两个文件的 DDL 是幂等的，但 DML 是裸 `INSERT`，不适合在应用启动时重复执行。

## Goals / Non-Goals

**Goals:**

- 提供可构建应用镜像的 Dockerfile，保证 Java 17、Maven 构建、静态资源、本地 ONNX 模型和配置文件进入最终镜像。
- 更新 Docker Compose，使 `docker-compose up` 能启动应用、PgVector 和 MySQL，并通过 `http://localhost:8080` 访问项目。
- Compose 内部连接使用 service name，避免应用容器内继续访问 `localhost` 数据库地址。
- 在 `com.example.rag.init` 下新增 MySQL 初始化能力，按 `classpath:db/init/business-data.sql` 和 `classpath:db/init/conversation-billing-schema.sql` 的表结构与演示数据初始化缺失内容。
- 删除与 classpath SQL 内容一致的项目顶层 `sql/` 重复副本，避免 Docker、README 和应用初始化出现多套 SQL 来源。
- 初始化必须幂等：重复启动应用不能重复插入演示数据，也不能覆盖用户已有数据。
- 保持现有 API、前端、RAG、Tool Calling、计费公式和租户隔离语义不变。

**Non-Goals:**

- 不引入 Flyway、Liquibase 或独立迁移框架。
- 不把 PgVector 向量表改为自定义初始化；Spring AI 的 `initialize-schema: true` 继续负责向量库表结构。
- 不新增独立前端工程、不改变静态资源发布方式。
- 不在 Dockerfile 或 compose 中写入真实 LLM API Key。
- 不清空、重置、覆盖已有 MySQL 数据。

## Decisions

### 1. 使用应用容器负责 MySQL 初始化

MySQL 官方镜像的 `/docker-entrypoint-initdb.d` 只在数据目录首次创建时执行，而且当前 SQL 文件包含大量裸 `INSERT`。如果继续把 `./sql` 挂载到 MySQL entrypoint，首次启动可以导入数据，但后续 schema 演进和缺失数据补齐不可控。

本变更改为应用启动时通过 `erpJdbcTemplate` 执行初始化：

- DDL 参考两个 SQL 文件中的 `CREATE TABLE IF NOT EXISTS`，可重复执行。
- DML 通过 `exists -> insert` 的受控方法执行，按业务键判断数据是否已经存在。
- 初始化只访问 ERP MySQL 数据源，不访问 PgVector 数据源。

备选方案是直接解析并执行两个 SQL 文件。该方案会被裸 `INSERT` 的重复执行问题卡住，且需要复杂 SQL 分割和错误处理，不如显式维护幂等插入逻辑可控。

### 2. Compose 使用环境变量覆盖数据源地址

保留 `application.yml` 对本机开发的默认配置，同时在 `docker-compose.yml` 的 `app.environment` 中覆盖容器运行时数据源：

```yaml
SPRING_DATASOURCE_PGVECTOR_URL: jdbc:postgresql://pgvector:5432/rag_demo
SPRING_DATASOURCE_PGVECTOR_USERNAME: postgres
SPRING_DATASOURCE_PGVECTOR_PASSWORD: postgres
SPRING_DATASOURCE_ERP_URL: jdbc:mysql://mysql:3306/erp?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8&allowPublicKeyRetrieval=true
SPRING_DATASOURCE_ERP_USERNAME: root
SPRING_DATASOURCE_ERP_PASSWORD: ${ERP_DB_PASSWORD:-mm#20250912}
```

MySQL 容器的 `MYSQL_ROOT_PASSWORD` 与应用的 `SPRING_DATASOURCE_ERP_PASSWORD` 必须来自同一个变量，避免数据库可启动但应用认证失败。

### 3. 中间件使用 healthcheck 控制启动顺序

`depends_on` 只保证容器启动顺序，不保证数据库已经可连接。Compose 中为 MySQL 和 PgVector 增加健康检查：

- MySQL 使用 `mysqladmin ping`。
- PgVector 使用 `pg_isready`。
- 应用服务依赖两个中间件的 `service_healthy`。

应用自身不强制新增健康检查端点，避免为部署补齐引入 actuator 依赖。完成后通过访问 `/` 或 `/index.html` 验证静态页面可用，通过接口请求验证后端可用。

### 4. Dockerfile 保持多阶段构建

Dockerfile 使用 Maven 构建阶段和 JRE 运行阶段：

- 构建阶段使用 Java 17 Maven 镜像，复用 `deploy/settings.xml` 加速依赖下载。
- 运行阶段使用 Java 17 JRE 镜像，只复制最终 jar。
- 默认暴露 `8080`，入口为 `java -jar app.jar`。

Compose 默认使用 README 中的远程镜像启动应用，根目录 `Dockerfile` 作为打包当前镜像的统一入口。原 `deploy/Dockerfile` 与根目录 Dockerfile 构建指令一致且不再被 README 或 Compose 引用，删除旧副本以避免两个 Dockerfile 内容漂移。

### 5. 种子数据按业务键幂等插入

初始化器为每类种子数据定义稳定业务键：

- 租户/用户：`ent_code`、`ent_code + user_id`
- 对话/消息：`conversation_id`、`message_id`
- 计费配置：`plan_code`、`model + effective_date`
- 计费账户/流水/账单：`ent_code`、`transaction_no`、`invoice_no`
- ERP 业务表：订单号、单据号、批次号、流水号等现有 SQL 中稳定可识别的业务字段组合

当业务键已存在时跳过该条种子数据，不更新已有记录。这样可以保护用户在演示数据基础上手工修改的数据。

### 6. 初始化 SQL 与日志文件的版本化边界

初始化 SQL 放入 `src/main/resources/db/init/`，使 Maven jar 和应用镜像都能通过 classpath 加载；项目顶层 `sql/` 中内容完全一致的重复 SQL 已删除。非归档 OpenSpec、README 和初始化类注释都应引用 `classpath:db/init/...`，避免继续指向已删除路径。

`docs/logs/20260630-containerized-runtime-init/` 用于记录本 change 的测试报告、自查报告和 AI 交互记录。由于项目原 `.gitignore` 会忽略 `logs/` 和 `*.log`，需要为 `docs/logs` 增加例外规则，确保 change 级别日志能进入版本控制。

## Risks / Trade-offs

- [风险] 初始化逻辑与 SQL 文件内容未来可能漂移。
  [缓解] 初始化类注释明确来源 SQL 文件；实现时按表分组，字段顺序与 SQL 文件保持一致，新增表或种子数据时同步维护。

- [风险] 应用启动时执行大量 DDL/DML 会拉长首次启动时间。
  [缓解] Demo 数据规模较小；DDL 使用 `CREATE TABLE IF NOT EXISTS`，DML 仅做存在性查询和缺失插入，可接受。

- [风险] 多个 app 副本同时首次启动时可能竞争插入同一条种子数据。
  [缓解] 当前 compose 只启动单个 app 副本；对已有唯一键的表可优先使用唯一键兜底。后续需要多副本部署时再引入迁移锁或数据库级 upsert。

- [风险] Compose 中 API Key 未配置时，页面可打开但实际 LLM 问答失败。
  [缓解] Compose 只透传环境变量，不写入默认密钥；README 以命令行环境变量启动为主，并保留 `.env` 可选示例，说明至少配置一个真实模型 Key 才能完成问答。

- [风险] `.dockerignore` 排除必要文件导致镜像内缺资源。
  [缓解] Dockerfile 仅依赖 `pom.xml`、`src/` 和 `deploy/settings.xml`；初始化 SQL 已放入 `src/main/resources/db/init/`，不再依赖根目录 `sql/`。

## Migration Plan

1. 新增根目录 Dockerfile，并确认 `.dockerignore` 不排除构建所需文件；删除不再使用的 `deploy/Dockerfile`，保留构建仍依赖的 `deploy/settings.xml`。
2. 更新 `docker-compose.yml`，加入使用远程镜像的 `app` 服务、数据库健康检查、统一密码变量和容器内数据源覆盖。
3. 新增 `com.example.rag.init` 初始化类，先实现 MySQL 表结构创建，再实现种子数据幂等插入。
4. 将初始化 SQL 迁移为 `src/main/resources/db/init/` 下的 classpath 资源，确认与顶层 `sql/` 原文件逐字节一致后删除顶层重复副本。
5. 更新 README 的远程镜像、当前镜像本地打包和可选推送说明，并为 `docs/logs` 增加 `.gitignore` 例外。
6. 本地验证以 `mvn test`、`docker compose config`、`openspec validate --all --strict`、`git diff --check` 和非归档范围路径扫描为准；真实 `docker compose up`、HTTP 接口访问和数据库容器联调按用户要求不纳入本地门禁。
7. 如需回滚，删除 `app` 服务与初始化类即可恢复为手工启动中间件和手工导入 SQL 的旧模式；已创建的表与数据不做自动回滚。

## Open Questions

- 无。README 已更新；MySQL `13306:3306` 宿主机端口映射保留，便于本地排查。
