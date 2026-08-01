# 自查报告

- Change ID: tool-result-chart-visualization
- Latest Review Time: 2026-08-01 15:20:29
- 变更范围：本轮提交仅同步五份 OpenSpec delta spec 到主规范、归档 `tool-result-chart-visualization` change，并将中英文 README 的 20 条图表测试话术表收敛为“序号、图表、测试话术”三列；未修改 Java、JavaScript、数据库或运行配置。
- OpenSpec 材料：已读取归档目录中的 `proposal.md`、`design.md`、`tasks.md` 和五份 delta spec；任务 169/169 已完成。主规范共 8 份严格校验通过，当前活动 change 列表为空。

## 执行记录

| 时间 | 变更范围摘要 | 结论 |
| --- | --- | --- |
| 2026-07-31 19:05:55 | 对照 OpenSpec 复核当前完整 Git 变更、Spring Bean 依赖、原问答/计费/Tool/历史逻辑、前后端图表协议和最新修复 | 通过 |
| 2026-08-01 00:07:59 | 修复英文自动模式业务数据识别、水位图可信范围、报告漂移和新增前端函数 JSDoc，并重新执行完整门禁 | 通过 |
| 2026-08-01 00:32:01 | 修复英文直接业务查询、多系列自动绑定、甘特进度与水平时间范围、SSE 错误气泡复用和项目上下文漂移，并执行完整门禁 | 通过 |
| 2026-08-01 13:22:33 | 复核流式最终答案标记分片、工具结果单次解析、Spring Bean 依赖和原业务逻辑，并执行当前完整本地门禁 | 通过 |
| 2026-08-01 14:50:51 | 复核知识文档资源预算、知识问答专用检索、README 双语示例与截图，并重新执行完整构建和提交前门禁 | 通过 |
| 2026-08-01 15:20:29 | 复核五份 delta spec 与主规范同步结果、归档材料完整性、活动 change 状态及中英文 README 三列表格结构 | 通过 |

## 问题清单

无新的阻塞或中高风险问题。

| 状态 | 严重级别 | 文件/行号 | 问题 | 建议 |
| --- | --- | --- | --- | --- |
| 已解决 | 中 | `src/main/java/com/example/rag/dao/mapper/ChatConversationMapper.java:67` | 会话状态、历史读取和归档曾只依赖租户或会话 ID，存在同租户跨用户访问风险。 | 已统一增加当前用户所有权约束，并补充跨用户拒绝测试。 |
| 已解决 | 中 | `src/main/java/com/example/rag/chat/chart/compile/ChartPlanValidator.java:109` | 部分转换、聚合、排序、通道基数和单位声明可能被静默忽略或产生错误业务语义。 | 已按图表类型建立转换与通道白名单，并在编译前交叉校验字段、聚合、单位、范围和来源。 |
| 已解决 | 中 | `src/main/java/com/example/rag/chat/chart/protocol/ChartSpecCodec.java:24` | 历史 `chart_spec` 若只做浅层 JSON 解析，可能把非法通道、错误类型、空值或畸形日期发送给前端。 | 已增加版本、类型、维度、encoding、行值、options、雷达上界和日期格式的完整校验，非法历史图表降级为空。 |
| 已解决 | 中 | `src/main/java/com/example/rag/chat/chart/tool/ChartPlanToolCallback.java:57` | Provider 可能忽略 JSON Schema 的 `additionalProperties=false`，或提交超大、过深规划输入。 | 已在单次反序列化前后执行字段白名单、字节、深度、容器宽度和节点总量校验。 |
| 已解决 | 中 | `src/main/resources/static/app.js:412` | 类型化 SSE 的 CRLF 分片、缺少 `done`、图表提前渲染或渲染失败可能造成残留卡片和状态错乱。 | 已实现跨分片换行归一、pending chart、`done` 后渲染、提前中断处理及失败容器释放。 |
| 已解决 | 低 | `src/main/java/com/example/rag/chat/chart/compile/ChartCompiler.java:837` | 普通类目同时包含日期形字符串和普通文本时，曾可能被误判为日期/字符串类型冲突。 | 已仅允许甘特图 `start`/`end` 通道识别日期形字符串，普通类目统一保持字符串类型。 |
| 已解决 | 低 | `src/test/java/com/example/rag/chat/AssistantBeanDependencyTest.java:55` | `ErpAssistantService` 拆分和新增图表组件后需要防止构造器注入形成循环依赖。 | 已增加依赖图测试；当前依赖方向单向且测试通过。 |
| 已解决 | 中 | `src/main/java/com/example/rag/chat/guard/BusinessDataTurnGuard.java:33` | 自动模式曾只识别中文业务数据问题，英文问题可能跳过当前轮业务查询守卫。 | 已补充英文 ERP 业务对象与数据意图双条件，并覆盖英文、中英混合及英文知识问题。 |
| 已解决 | 中 | `src/main/java/com/example/rag/chat/chart/compile/ChartPlanFactory.java:596` | 单值水位图曾以当前值作为上界，百分比 80 会错误归一为 100%。 | 已按 0～1 比例或 0～100 百分比生成可信上界，超过范围继续由编译器拒绝并降级文本。 |
| 已解决 | 低 | `src/main/resources/static/chart-adapter.js:19` | 新增前端图表方法缺少完整中文 JSDoc 参数、返回值和异常分支说明。 | 已补齐适配器及新增 SSE/消息图表方法 JSDoc，并通过结构检查。 |
| 已解决 | 中 | `src/main/java/com/example/rag/chat/guard/BusinessDataTurnGuard.java` | `What are the sales orders`、`Give me sales orders` 等不含显式查询动词的英文直接请求可能跳过当前轮业务数据守卫。 | 已增加受限的英文业务记录问句/祈使句模式，并以产品规格问题验证不误判知识问答。 |
| 已解决 | 中 | `src/main/java/com/example/rag/chat/chart/compile/ChartPlanFactory.java` | 自动规划未绑定可选 `series`，会把按月份对比各产品的数据合并为单系列。 | 已按标题语义、系列基数和字段占用自动绑定系列；聚合时按主轴与系列联合分组。 |
| 已解决 | 中 | `src/main/resources/static/chart-adapter.js` | 原甘特图复用竖向温度范围扩展，横纵轴与任务时间语义相反。 | 已改为应用内固定水平范围渲染函数，使用时间横轴、任务类别纵轴并叠加可选完成进度。 |
| 已解决 | 中 | `src/main/resources/static/app.js` | SSE `error` 或连接异常在当前助手气泡之外再创建错误消息，可能留下空白或重复气泡。 | 已复用当前消息句柄，保留已接收的安全正文并在同一气泡追加错误提示。 |
| 已解决 | 低 | `openspec/context/spring-ai-rag-demo.md` | 项目上下文仍声明旧 Spring Boot/Spring AI 版本和无 MyBatis，与当前工程事实不一致。 | 已同步为 Spring Boot 4.0.7、Spring AI 2.0.0 及 MyBatis-Plus/JdbcTemplate 持久层事实。 |
| 已解决 | 中 | `src/main/java/com/example/rag/chat/output/AssistantAnswerSanitizer.java:323` | 流式取消或异常恰好发生在 `<!--FINAL_ANSWER-->` 标记的合法非空前缀后时，未完整识别的标记残片可能进入用户正文或历史消息。 | 已按完整标记的尾部真前缀统一暂存和丢弃，并覆盖所有合法非空前缀、正常正文及取消收口场景。 |
| 已解决 | 低 | `src/main/java/com/example/rag/chat/chart/capture/ToolResultRecorder.java:198` | 接近输入上限的业务 Tool JSON 曾分别执行空结果判断和行解析，产生重复反序列化和不必要的瞬时内存占用。 | 已由 `ParsedRows` 在一次受限解析中同时返回行数据与显式空结果状态，主流程不再重复解析。 |
| 已解决 | 中 | `src/main/java/com/example/rag/chat/DocumentLoaderService.java` | 仅放大上传请求体限制不能解决单文档 Token 超限，超长文档还可能在解析、切分和向量写入阶段造成不受控资源占用。 | 已增加 500 万字符、2 万分片和真实 WordPiece 128 Token 上限，按 100 条分批写入，并在同租户同来源覆盖及失败时清理残留数据。 |
| 已解决 | 中 | `src/main/java/com/example/rag/chat/client/AssistantClientProvider.java` | knowledge 模式复用 ERP 系统提示词时，会把已召回的 JVM 等非 ERP 文档误判为超出业务范围。 | 已使用不装配 Tool 的知识问答专用客户端，并以 8 条召回和 0.25 阈值覆盖中文技术文档；租户过滤保持不变。 |
| 已解决 | 低 | `README.md`、`README_EN.md` | README 对知识库范围、图表能力、测试话术和示例截图的描述曾与最终实现不完整或不一致。 | 已同步中英文功能说明、20 条可复制图表话术和 6 张示例图；顶部环形图与后续编号避免重复，所有本地图片引用均已校验。 |

## OpenSpec 一致性

- 业务 Tool 结果只在当前 `traceId + entCode + conversationId` 生命周期内有界保存，不重新查询业务库，也不持久化原始结果。
- `ChartPlanToolCallback` 只接收 LLM 选择的图表类型和标题；来源、字段绑定、转换、安全 options 和最终业务数值均由后端从捕获结果生成。
- `ChartVO.ChartType` 统一约束 23 种图表类型，并保持小写连字符 JSON 编码及历史数据兼容。
- 每轮只接受第一个通过完整校验的图表，多业务 Tool 时只选择一个来源结果；空、错误或不可图表化结果保持纯文本。
- 非流式返回可空 `chart`；流式接口直接使用 `delta`、`chart`、`done`、`error` 类型化 SSE，不维护 V1/V2 双协议。
- `chart_spec` 与助手消息保存并供历史详情和续聊回放；旧消息、空字段和非法历史 JSON 均降级为 `chart = null`。
- 前端使用本地 ECharts 和固定适配器生成声明式 option，不接收服务端函数、HTML、URL、CSS 或任意 ECharts 配置。
- 自动规划仅在标题明确且系列值基数受控时绑定 `series`；甘特进度仅接受 0～1，前端固定绘制水平时间范围，不再加载通用 bar-range 扩展。
- 流式前端收到错误事件或连接异常时复用当前助手消息，不生成重复气泡；已接收正文仍可保留。
- 内部图表规划 Tool 位于业务 Tool 快照之外，不写入业务 Tool 调用次数、流水或管理列表；knowledge 模式不装配该 Tool。
- `ErpAssistantService` 已拆分为编排层、`AssistantClientProvider`、`AssistantLifecycleService` 和 `chat.dto`，图表能力按职责放入下一层子包。
- 最终答案边界支持跨分片识别；异常或取消停在标记前缀时丢弃协议残片，合法业务正文和 Markdown 仍保持流式输出。
- 业务 Tool 结果在资源预算内只反序列化一次；显式空结果与不支持结构继续使用原有降级语义，不影响文本回答。
- 知识文档导入对请求体、解析字符、分片数量、真实 Token 和批次大小实施分层预算；同租户同来源使用覆盖语义，失败批次执行清理。
- knowledge 模式使用知识库专用系统提示词和更宽召回参数，不装配业务 Tool；auto/data 的原有 Tool 与 RAG 策略未被 README 修订改变。
- 五份 delta spec 已同步到主规范；`tool-result-chart-visualization` 已归档到 `openspec/changes/archive/2026-08-01-tool-result-chart-visualization/`，proposal、design、169 项任务和完整 delta specs 均保留。
- 中英文 README 仅移除测试话术表中的建议截图名列，20 种图表及其可复制话术保持一致，未改变产品协议或运行逻辑。

## 非功能审查

- 并发：`ToolResultRecorder` 按 trace 隔离上下文，首个有效图表采用原子接受语义；流式收口使用 `AtomicBoolean` 防止完成、异常和取消重复持久化或计费；Bean 构造器依赖图无环。
- 安全：业务结果读取同时校验 trace、租户和会话；会话查询增加用户所有权；规划和历史协议均有字段白名单、类型校验和资源预算；日志不输出原始 Tool 行、SQL或堆栈。
- 边界：覆盖空结果、标量、无效 JSON、超大/过深结构、重复规划、未知字段、错误单位、非法范围、空值、历史坏数据、英文直接查询、系列联合分组、甘特进度、最终答案标记跨分片、流式异常和取消，以及超长文档、真实 Token 切分、批次失败清理和知识问答非 ERP 文档。
- 性能：正常图表选择复用原问答 Tool Calling 循环；仅在漏查询或漏选择时各允许一次有界模型调用，不新增业务 SQL；原始结果、最终行数和协议大小均有限制，业务 Tool JSON 单次解析，文档向量按 100 条分批写入，终止路径清理短生命周期数据。
- 回归风险：auto/data/knowledge 的模型路由、RAG、会话记忆、业务 Tool、计费和日志语义保留；协议变更由同仓前端同步消费，历史数据使用可空字段兼容。
- 本轮文档变更：未新增 Spring Bean、运行依赖或可执行代码，不影响并发、安全、性能、原业务逻辑和历史兼容；如需回退，只需恢复 change 目录、主规范和 README 表格列。

## 测试缺口

- 既有实现已覆盖 194 项 Java 测试和 33 个前端 fixture；本轮仅修改规范与文档，因此未重复执行 Maven、Node 或浏览器操作。已针对本轮实际变化完成 8 份主规范严格校验、归档完整性、169 项任务完成度、中英文 README 20 行三列表格及 Git 差异检查，未发现阻塞性缺口。

## 结论

- 结果：通过
- 摘要：最终实现已同步到主规范并完成归档；本轮未修改 Spring Bean 或运行代码，原问答、计费、业务 Tool 和历史主流程均未变化，中英文 README 调整符合用户要求。
