# 自查报告

- Change ID: containerized-runtime-init
- Latest Review Time: 2026-06-30 12:41:52
- 变更范围：Dockerfile、docker-compose.yml、README/README_EN、application.yml、OpenSpec 主规范与归档 change 文档、`ErpDatabaseInitializer`、classpath 初始化 SQL、初始化器测试、删除重复 `deploy/Dockerfile`、README 命令行环境变量启动说明。
- OpenSpec 材料：已读取 `proposal.md`、`design.md`、`tasks.md`、`specs/containerized-runtime-init/spec.md`，并同步更新 `openspec/specs/data-access-mybatis-plus-crud/spec.md` 的 SQL 资源路径。

## 执行记录

| 时间 | 变更范围摘要 | 结论 |
| --- | --- | --- |
| 2026-06-30 11:10:30 | 容器化运行入口、Compose 远程镜像、ERP MySQL 幂等初始化、SQL 资源迁移、文档和验证记录 | 通过 |
| 2026-06-30 12:39:34 | 复查重复 Dockerfile、README 中 docker-compose 直接命令传入 AI Key、OpenSpec 和日志收敛、提交前门禁 | 通过 |
| 2026-06-30 12:41:52 | 归档 `containerized-runtime-init`，同步主 spec 并补齐主规范 Purpose | 通过 |

## 问题清单

| 状态 | 严重级别 | 文件/行号 | 问题 | 建议 |
| --- | --- | --- | --- | --- |
| 无 | - | - | 未发现阻断提交的问题。 | 无需修改。 |

## OpenSpec 一致性

- Dockerfile：已提供 Java 17 Maven 多阶段构建入口，运行阶段复制 Spring Boot jar 并暴露 8080；`deploy/Dockerfile` 与根目录 Dockerfile 构建指令一致且不再被 README 或 Compose 引用，已删除旧副本，保留构建仍依赖的 `deploy/settings.xml`。
- Docker Compose：`app` 使用 README 指定的远程镜像 `ly753/spring-ai-rag-demo:latest`，通过环境变量覆盖容器内 PgVector 和 ERP MySQL 数据源地址，依赖 MySQL/PgVector healthcheck 后启动，符合“一键启动完整运行时”要求。
- README：docker-compose 启动说明已改为命令行环境变量优先，`.env` 仅作为可选固定配置方式；本地未保留默认 `.env` 文件，避免误提交或误认为必须创建。
- 初始化逻辑：`ErpDatabaseInitializer` 使用 `@Qualifier("erpJdbcTemplate") JdbcTemplate`，仅访问 ERP MySQL；DDL 直接执行 `CREATE TABLE IF NOT EXISTS`；DML 按 `SEED_KEYS` 做 `exists -> insert` 幂等判断，符合表结构和演示数据初始化要求。
- SQL 资源：顶层 `sql/` 与 classpath SQL 逐字节一致后已删除，当前保留 `src/main/resources/db/init/business-data.sql` 和 `conversation-billing-schema.sql`，并更新非归档引用到 `classpath:db/init/...`。
- OpenSpec 归档：`openspec archive containerized-runtime-init --yes` 已将 delta spec 同步到 `openspec/specs/containerized-runtime-init/spec.md`，原 change 已移动到 `openspec/changes/archive/2026-06-30-containerized-runtime-init/`；主规范 Purpose 已补齐，不保留默认 `TBD`。
- 业务兼容：未改动现有 Controller、Tool、RAG、计费公式、对话历史和前端业务逻辑；初始化只补齐缺失表和缺失演示数据，不清空、不覆盖已有业务数据。

## 非功能审查

- 并发：当前 Compose 只启动单个 app 副本，`exists -> insert` 足以满足本地一键启动；多副本首次启动仍可能竞争插入同一条演示数据，OpenSpec 已记录为后续多副本部署再引入初始化锁或数据库级 upsert 的风险。
- 安全：Dockerfile、docker-compose.yml、README 和 application.yml 未写入真实 LLM API Key；docker-compose 可通过当前命令行会话的环境变量传入 AI Key。初始化 SQL 来自 classpath 固定资源，不接收外部输入。`buildSingleInsertSql` 复用受信任资源内 SQL，不扩大运行期用户输入注入面。
- 边界：初始化器对缺失 SQL 资源、无法解析 INSERT、字段和值数量不一致、缺少幂等键配置均会抛出 `IllegalStateException`，启动期暴露配置或脚本错误，符合快速失败预期。
- 性能：首次启动会执行 DDL、存在性查询和缺失插入，当前 demo 数据规模较小；重复启动主要是存在性查询，不涉及运行期热路径。
- 回归风险：移除 MySQL entrypoint 的 `./sql` 挂载后，初始化责任转移到应用启动；已通过内置 SQL 解析测试和 `mvn test` 验证，真实数据库首次/重复启动仍需 Docker 环境补充验证。

## 测试缺口

- 未执行真实 Docker 容器运行时验证，包括 `docker compose up`、远程镜像拉取、应用首页访问、MySQL 首次建表和重复启动数据检查。原因是用户已明确要求去掉外部测试部分，且当前本地验证只覆盖不依赖运行中 Docker daemon 的路径；`docker compose config` 已覆盖命令行环境变量注入后的配置解析。
- 未执行性能压测和多副本并发初始化测试；当前需求为单 app Compose 启动，不要求覆盖多副本部署。

## 结论

- 结果：通过
- 摘要：当前实现与 OpenSpec `containerized-runtime-init` 保持一致，未发现 Spring Bean 循环依赖、真实密钥固化、原业务逻辑偏移、无用重复部署文件或 SQL 资源迁移残留引用问题。
