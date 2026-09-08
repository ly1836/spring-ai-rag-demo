# AI 交互记录

## 元数据

- 变更 ID：knowledge-base-citations-and-rag-evaluation
- 最近更新：2026-09-08 23:50:29
- 开发者：leiyang
- AI工具：Codex

## 记录更新

- 2026-09-08 15:50:17：根据当前可见交互、完整 Git 工作区和 OpenSpec 材料，首次建立本 change 的 AI 交互记录、自查报告和本地验证报告；记录复审发现、已采纳修复、验证证据与剩余收尾项。
- 2026-09-08 15:55:33：用户要求处理审查报告中仍存在的暂存范围风险；已将最终代码、OpenSpec 更新和三份日志统一纳入暂存区，并把自查结论从“有风险通过”更新为“通过”。
- 2026-09-08 20:05:10：用户反馈知识库回答不准，确认替换嵌入模型后再次要求 review，并明确“全部按建议修复”；已补齐旧模型文档识别与重新导入提示，更新公共说明和三份报告，重建本地容器并用原文件重新导入两份现有文档。
- 2026-09-08 21:42:12：用户再次要求使用 `self-review`、`test-report`、`chat-history` 更新当前 change 记录，并强调审查报告不列外部测试或压测；已按现有格式增量复核 128 个暂存文件、本地测试、模型资源和 Spring Bean 依赖结论。
- 2026-09-08 23:21:25：最新复审指出模型说明误写了不存在的 Dockerfile 模型校验，且三份 change 记录仍使用旧统计；用户决定保持 Dockerfile 和 docker-compose 不变，仅修正发布说明，并要求三份报告只保留本地验证内容。已按当前 126 个文件、122/122 项任务和 343 项本地测试完成同步。
- 2026-09-08 23:50:29：用户要求先同步主规范再归档 change，随后检查提交密钥、重新构建本地 `4.0.0` 并让 `latest` 指向同一镜像、重启应用容器，最后执行 Git 提交门禁；已完成规范同步与归档、敏感信息扫描、镜像和容器验证，并重新运行 343 项本地测试。

## 关键提示词

- 用户要求审查当前模块全部 Git 变更，以带序号和“模块名”列的统一表格输出，并重点检查新增 Spring Bean 注入是否形成循环依赖。
- 用户要求核对原有业务逻辑是否变化，以及变化是否符合知识库、引用和 RAG 评测需求；问题描述使用易懂中文并给出明确结论。
- 首轮审查发现问题后，用户明确要求“全部按建议修复”。
- 修复完成后，用户再次要求按相同口径复审当前完整工作区。
- 用户要求使用 `self-review`、`test-report`、`chat-history` 三个技能更新 change 级日志，并明确审查报告不要写外部测试、压测等测试分类，格式参考仓库已有报告。
- 用户反馈知识库对渠道方案、JVM 和 RabbitMQ 的回答不准，询问能否替换 `all-MiniLM-L6-v2`，并在复审后要求全部按建议处理。
- 用户明确要求嵌入模型和对应分词器都下载到本地，不允许运行时再从远程拉取。
- 用户明确决定 Dockerfile 不变，通过调整说明落实模型资源检查；三份 change 报告不列外部测试和压测内容。
- 用户要求将 `knowledge-base-citations-and-rag-evaluation` 先同步到主规范再归档，并在提交前确认仓库没有不能提交的密钥。
- 用户要求使用当前代码重新构建本地 `4.0.0`，把 `latest` 更新到同一镜像并重新启动现有容器，同时保留数据库数据。
- 用户最终调用 `git-commit`，要求按 OpenSpec 提交门禁生成本地提交，不要求推送。

## 重要 AI 建议

- 建议区分文档参数/业务异常和解析、嵌入、PgVector 等基础设施异常，后者返回固定安全的 `SYSTEM_ERROR`，避免把系统故障误报成业务状态问题。
- 建议评测清理失败不能只写日志，应继续尝试其余清理步骤，把最终失败项写入 JSON/Markdown 报告并作为构建硬门禁。
- 建议正常默认知识库读取先走无锁快速路径，仅在首次创建、重复默认标记或状态异常时进入租户锁修复，减少同租户热点等待。
- 建议扩展 Spring Bean 构造器依赖图测试，并人工复核 Controller、问答编排、RAG、知识库和文档服务的依赖方向。
- 建议提交前统一纳入暂存区中的最终代码、OpenSpec 更新和三份日志，避免只提交旧暂存快照。
- 建议不能只按 MySQL 的 `ready` 状态判断文档可用，应在文档版本中保存成功入库时的嵌入模型；旧记录返回 `requiresReindex` 并在页面显示“需重新导入”。
- 建议使用原文件重新生成当前模型向量，不根据 384 维或旧向量文本猜测、回填模型身份。
- 建议把公共配置、模块上下文、README 和 change 日志里的旧模型、旧组件名及旧统计数同步到最终事实。
- 建议不要在 Dockerfile 中重复维护模型哈希；发布前使用 Git LFS 对象检查和 `EmbeddingModelResourceTest` 校验本地资源，并让说明与实际构建流程保持一致。
- 建议归档前先把六份 delta spec 同步到主规范，保留归档材料并对全部主规范执行严格校验。
- 建议重建时只强制替换 `rag-demo` 应用容器，保留 MySQL、PgVector 容器和数据卷；以镜像 ID、启动日志和 HTTP 200 共同确认结果。

## 开发者决策

- 采纳全部首轮 review 建议：新增知识基础设施异常分类、评测清理汇总和硬门禁、默认知识库健康读取快速路径，以及对应测试和 OpenSpec 任务 10.46～10.48。
- 保留原有三种问答模式边界：`auto` 使用受管 RAG 与业务 Tool，`data` 只使用业务 Tool，`knowledge` 使用受管 RAG 且不装配业务 Tool。
- 保留 `/api/load`、`/api/upload` 兼容入口，通过默认知识库完成受管导入，不新增第二套旧向量检索逻辑。
- 采用统一日志目录 `docs/logs/20260907-knowledge-base-citations-and-rag-evaluation/`，以 change 最早可确认创建日期 2026-09-07 作为日期前缀。
- 按用户要求把代码审查与测试分类分开：`self_review.md` 只保留代码、OpenSpec 和本地风险，测试执行状态统一写入 `test_report.log`。
- 采纳暂存范围收口建议：最终代码、OpenSpec 和三份日志统一进入暂存区，未执行提交或推送。
- 采纳旧模型文档处理建议：新增 `embedding_model` 持久化和幂等补列，接口增加 `requiresReindex`，前端显示“需重新导入”并保留替换入口。
- 采纳原文件重导方案：使用 `D:\work\mp-资料\工作资料\渠道\渠道技术方案.docx` 和桌面 PDF 完成当前默认知识库的版本替换，不从向量片段反拼文件。
- 采纳本地资源交付方案：ONNX 模型和分词器均通过 Git LFS 管理，本地资源测试校验固定 SHA-256，运行时只从 classpath 加载。
- 采纳说明与报告同步建议：最终变更范围为 126 个文件，OpenSpec 任务为 122/122，本地测试为 50 个测试类、343 项测试。
- 保持根目录 Dockerfile 和 `docker-compose.yml` 不变；模型哈希只由本地资源测试维护，发布说明补充 LFS 对象检查和资源专项测试。
- 采纳“先同步再归档”：六份 delta spec 已合并到主规范，change 归档到 `openspec/changes/archive/2026-09-08-knowledge-base-citations-and-rag-evaluation/`。
- 采纳提交前敏感信息检查：未发现真实密钥；本地 `.env` 保持忽略且未进入暂存区。
- 采纳本地镜像发布方式：`4.0.0` 与 `latest` 指向同一新镜像，仅重建应用容器，数据库容器及数据卷保持不变。

## 已拒绝建议

- 不在本次三份 change 报告中罗列外部测试和压测内容，只记录本地自动化、本地接口及已有本地容器验证事实。
- 不在 Dockerfile 中增加模型哈希校验，避免通用镜像构建脚本与模型版本绑定；改由发布前检查和本地资源测试负责。

## 已讨论风险

- 错误分类风险：向量基础设施故障若落入 `IllegalStateException` 会返回错误的业务提示，现已通过专用异常和全局映射解决。
- 数据清理风险：评测异常结束或部分清理失败可能留下随机租户和向量数据，现已增加继续清理、失败汇总和硬门禁。
- 并发性能风险：健康默认知识库读取不应每次锁租户行，现已改为异常时才加锁修复。
- Bean 依赖风险：新增 `RagAnswerService`、`KnowledgeDocumentService`、`KnowledgeDocumentIngestionService` 和 Controller 可能扩大依赖图；人工检查与专项测试均未发现闭环。
- 业务回归风险：`auto` 接入受管 RAG 时必须保留 Tool、会话、模型路由和单次计费；`data` 不得访问向量库；`knowledge` 不得注册业务 Tool。当前代码和测试均保持这些边界。
- 交付范围风险：此前 19 个文件包含未暂存的最终修复；现已统一更新暂存区，该风险已解决。
- 状态误导风险：旧模型文档在 MySQL 中仍为 `ready`，但当前模型检索会排除旧向量；现已通过 `requiresReindex` 明示，并重新导入当前两份存量文档。
- 说明漂移风险：OpenSpec 公共配置、模块 context、README 和 change 日志若仍保留旧模型、旧 Advisor 或旧统计，会误导后续维护；现已统一更新。
- 构建说明风险：Dockerfile 不执行资源测试，不能把它描述成会自动校验模型；现已把检查职责明确放在发布前的 LFS 检查和资源专项测试中。
- 规范漂移风险：只归档 change 而不同步 delta spec 会使主规范缺少最终需求；现已先同步 21 条新增要求和 3 条修改要求，再完成归档和严格校验。
- 提交泄密风险：README、配置、日志和本地环境文件可能误带真实密钥；仓库级扫描未发现真实私钥、API Token、JWT 或 URL 内嵌凭据，`.env` 未暂存。
- 容器替换风险：直接重建整个 Compose 项目可能影响数据库；本次只重建 `rag-demo`，MySQL、PgVector 与数据卷保持运行。

## 最终结果

- OpenSpec：`proposal.md`、`design.md`、`tasks.md` 和六份 delta spec 已覆盖知识库管理、文档版本、可信引用、SSE/历史回放、质量评测及兼容迁移；122/122 项任务完成，六份 delta spec 已同步到主规范并归档，11 份主规范严格校验通过。
- 异常分类：`KnowledgeInfrastructureException`、`KnowledgeDocumentIngestionService` 和 `GlobalExceptionHandler` 对应任务 10.46，保留 `PARAM_ERROR`、`BIZ_ERROR` 与 `SYSTEM_ERROR` 的清晰边界。
- 评测清理：`RagEvaluationCleanupSummary`、`RagEvaluationIT`、`RagEvaluationGate`、`RagEvaluationReport` 和 `RagEvaluationReportWriter` 对应任务 10.47，清理失败进入报告和硬门禁。
- 默认库读取：`KnowledgeBaseService.resolveDefaultKnowledgeBase` 和相关测试对应任务 10.48，健康读取不锁租户行，首次创建及异常修复仍受锁保护。
- Bean 依赖：`AssistantBeanDependencyTest` 已覆盖新增知识库、RAG、导入和 Controller Bean，专项测试通过。
- 模型迁移：`KnowledgeDocumentEntity`、`ErpDatabaseInitializer`、`KnowledgeDocumentService`、`KnowledgeVO` 和静态前端共同实现模型身份持久化、旧记录识别和“需重新导入”提示，对应任务 10.55；`openspec/config.yaml`、模块 context 和 README 对应任务 10.56。
- 本地模型资源：`model.onnx`、`tokenizer.json`、`.gitattributes`、`MODEL.md` 和 `EmbeddingModelResourceTest` 共同保证模型与分词器通过 Git LFS 下载到本地、由资源测试校验并在运行时离线加载；Dockerfile 保持不变，对应任务 10.49、10.58。
- 存量数据：默认知识库中的渠道文档已升级到版本 2、220 个分片，PDF 已升级到版本 3、1474 个分片；两者均为 `requiresReindex=false`，渠道、JVM 和 RabbitMQ 查询返回正确来源。
- 业务主链：`ErpAssistantService`、`AssistantLifecycleService`、`RagAnswerService` 和 `ChatHistoryService` 保持模型路由、会话、计费、Tool 与历史兼容边界，并增加同轮引用和真实 `ragDocCount`。
- 本地部署：当前代码已构建为 `ly753/spring-ai-rag-demo:4.0.0`，`latest` 指向同一镜像；`rag-demo` 重建后启动成功、重启次数 0、首页 HTTP 200，MySQL 与 PgVector 保持 healthy。
- 验证：完整本地测试 343 项通过，OpenSpec strict、Git 空白检查、Git LFS 对象、本地模型资源、Spring Bean 依赖和提交敏感信息检查通过；归档与日志更新后共 132 个文件进入最终暂存范围，尚未推送。
