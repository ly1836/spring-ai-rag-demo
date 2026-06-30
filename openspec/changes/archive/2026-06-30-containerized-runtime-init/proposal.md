## Why

当前项目的 Docker 编排只能启动 PostgreSQL/PgVector 和 MySQL，不能直接启动应用；同时 MySQL 初始化依赖手工导入 SQL，裸 `INSERT` 在重复执行时不具备幂等性，导致本地或容器化环境无法稳定一键启动并访问项目。

本变更将应用镜像、Docker Compose 中间件编排和应用启动时的 MySQL 表结构/种子数据初始化纳入同一套可重复执行的启动能力，确保开发者执行 `docker-compose up` 后即可访问 ERP 智能助手。

## What Changes

- 新增根目录应用 Dockerfile，使 Spring Boot 应用可在 Java 17 容器中构建并运行，打包时包含静态资源、本地 ONNX 嵌入模型和配置文件。
- 更新 `docker-compose.yml`，编排 `app`、`pgvector`、`mysql` 三个服务，`app` 默认使用 README 中的远程镜像并通过 service name 连接 PostgreSQL/PgVector 与 MySQL。
- 为 MySQL 与 PgVector 增加健康检查，并让应用服务等待依赖服务可用后启动。
- 使用环境变量覆盖 `application.yml` 中的本机数据库连接配置，确保容器内连接地址不再指向 `localhost`。
- 在 `com.example.rag.init` 下新增启动初始化能力，参考 `classpath:db/init/business-data.sql` 与 `classpath:db/init/conversation-billing-schema.sql` 创建 MySQL 表结构并初始化演示数据。
- 将初始化 SQL 作为 `src/main/resources/db/init/` 下的 classpath 唯一来源，删除与其内容一致的项目顶层 `sql/` 重复副本。
- 初始化逻辑必须幂等：表结构使用幂等 DDL，种子数据按表或业务唯一键判断是否存在后再插入，避免重复数据和唯一键冲突。
- 不改变现有 `/api/**` 对外接口、前端资源结构、RAG 检索语义、计费公式和多租户隔离语义。

## Capabilities

### New Capabilities

- `containerized-runtime-init`: 定义应用容器化运行、Docker Compose 一键启动、MySQL 表结构与演示数据幂等初始化要求。

### Modified Capabilities

- 无。现有 chat、billing、conversation、tool、data-access 相关能力的对外行为保持兼容，本变更只补齐部署与初始化入口。

## Impact

- 涉及业务域：`config`、`billing`、`conversation`、`tool`，以及新增 `init` 包和部署文件。
- 受影响文件：`Dockerfile`、`deploy/settings.xml`、`.gitignore`、`.dockerignore`、`docker-compose.yml`、`README.md`、`README_EN.md`、`src/main/resources/application.yml`、`src/main/resources/db/init/*`、`src/main/java/com/example/rag/init/*`、`docs/logs/20260630-containerized-runtime-init/*`。
- 中间件影响：PostgreSQL/PgVector 继续作为 `@Primary` 数据源承载向量库；MySQL 继续承载 ERP 业务、租户、对话、计费和用量表。
- 配置影响：LLM API Key 仍通过 `DEEPSEEK_API_KEY`、`DASHSCOPE_API_KEY`、`GOOGLE_GENAI_API_KEY` 注入，禁止写死到 Dockerfile 或 compose 默认值中。
- 数据影响：初始化只补齐缺失表和缺失演示数据，不应清空、覆盖或重置已有业务数据。
- 安全影响：初始化 SQL 不接收外部请求参数；运行期业务查询仍必须经过现有 `TenantContext`、`BaseTool` 或 MyBatis-Plus 租户隔离机制。
