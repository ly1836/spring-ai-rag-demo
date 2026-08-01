> 涉及业务域：chat、conversation、tool、vo、static frontend；billing、config 仅做兼容性与风险验证。

## Why

当前业务 Tool 查询结果只能由 LLM 转写为 Markdown，前端无法获得可直接渲染、可校验且可在历史消息中回放的图表数据。需要为 Tool 结果增加统一的结构化可视化协议，让 LLM 负责选择图表表达，后端负责从真实查询结果生成可信数据，前端负责一致渲染。

## What Changes

- 捕获每轮成功业务 Tool 的非空原始结果，并以 `traceId`、租户和会话为边界进行短生命周期隔离；回答结束、失败或取消后清理，不新增业务查询。
- 历史助手回答只能用于理解上下文，不能作为当前轮业务数据来源；auto/data 模式识别为业务数据问题但本轮没有业务 Tool 结果时，后端必须丢弃未验证回答并最多重试一次当前轮查询。
- 用户使用中文提问时最终回答必须全程使用中文，并禁止向用户暴露 Tool 名称、函数名称、数据库表名、字段名、SQL 或内部调用过程。
- 向启用 Tool Calling 的 LLM 暴露内部图表选择能力：LLM 只提交图表类型和标题，不能提交来源 Tool、字段映射、转换、展示选项或任意业务数值；选择 JSON 在单次解析前后受字节、深度、容器宽度和节点总量限制。
- 当前轮已经捕获可图表化业务数据但主回答模型漏掉图表选择时，后端必须使用当前模型补做一次结构化 `type/title` 选择，再复用同一后端规划与编译链路；不得由后端擅自选择图表类型。
- 将“两行及以上且包含数值字段，或可按状态、类型等分类计数”的成功业务结果明确判定为适合可视化；即使文本已使用 Markdown 表格，LLM 也必须在最终回答前单独提交图表规划。
- 后端根据已捕获的结构化业务数据自动生成来源选择、字段绑定、转换和安全展示选项；原始结果没有数值列但存在可比较分类时，后端按真实结构化记录数生成可信计数，再编译统一的版本化 `ChartSpec`。每轮问答最多接受一个有效图表，多 Tool 结果按标题相关性、结构兼容性和调用顺序选择一份作为唯一图表来源，不合并不同 Tool 结果。
- 后端自动绑定数值通道时排除订单号、工单号等数值型业务标识；标题直接出现自定义分类字段或地区、部门、渠道、品牌等业务别名时，可将其绑定为直角坐标图系列维度。
- 后端只在甘特图 `start`/`end` 时间通道解析日期形字符串；普通字符串类目即使部分值形似日期也保持文本语义，避免合法业务分类被误判为类型冲突。
- 后端使用统一 `ChartType` 枚举约束图表规划、校验、编译和响应类型，并保持现有小写连字符 JSON 编码及历史图表数据兼容。
- 仅对执行成功、结果非空且具备可视化意义的业务 Tool 生成图表；空结果、异常结果或不可图表化结果继续只返回文本。
- 支持至少 23 种图表：环形图、旭日图、条形图、瀑布图、子弹图、面积图、阶梯图、雷达图、散点图、气泡图、直方图、箱线图、热力图、桑基图、矩形树图、甘特图、漏斗图、词云图、仪表盘图、水位图、平行坐标图、折线图和饼图。
- 非流式问答响应增加可空 `chart` 字段；前后端一体部署的流式问答直接使用结构化 SSE，输出 `delta`、`chart`、`done`、`error` 事件，不再维护并行协议版本。
- 将最终 `ChartSpec` 持久化到助手消息；历史记录和续聊回放同一图表，不重新调用 LLM 或重新查询业务数据，并按语义通道类型及真实日期格式校验历史协议。
- 前端本地化引入 Apache ECharts 及必要扩展，通过统一适配层将 `ChartSpec` 转换为 ECharts option；编译器派生字段统一按 `encoding` 取值，不使用 CDN、不引入前端构建工具。
- 图表规划、编译、持久化或渲染失败不得中断文本回答；服务端记录可观测错误，前端降级为仅显示 Markdown。
- 后端自动规划候选校验失败时继续尝试本轮其他已捕获业务结果；只有全部结果确实无法满足 LLM 所选图表类型时才返回无图表结果，且不得向用户暴露绑定、校验或降级过程。
- 最终回答使用内部边界标记与 Tool Calling 中间文本隔离，服务端在非流式、流式和历史读取路径统一移除明确的查询、规划与重试旁白，只向前端返回业务答案；合法技术说明或业务建议不得因包含内部协议同名词而被误删。
- 内部图表规划 Tool 与业务 Tool 调用分开统计，不写入面向业务的 Tool 命中次数和调用流水。
- 将图表能力按 `model`、`capture`、`compile`、`protocol`、`tool` 职责放入 `chat/chart` 下一层子包，避免图表模型、捕获、编译、协议和 Tool 装配继续混放。
- 拆分过大的 `ErpAssistantService`：多 Provider 路由与 Tool 装配由 `AssistantClientProvider` 负责，消息、计费、Tool 流水和终止收口由 `AssistantLifecycleService` 负责，内部 DTO / record 统一放入 `chat.dto`。
- 知识库文档导入保留 500MB 单文件和 550MB 请求上限，同时对提取文本、最终分片数和单批向量写入施加内存资源预算；使用实际 ONNX WordPiece 分词器保证分片不超过 128 token，同租户同来源重新导入时覆盖旧向量。

## Capabilities

### New Capabilities

- `tool-result-chart-visualization`: 定义业务 Tool 结果捕获、LLM 图表规划、可信 `ChartSpec` 编译、23 种图表覆盖、前端渲染和失败降级的端到端行为。
- `knowledge-document-ingestion`: 定义知识库文档的受控文本提取、实际嵌入 token 边界、分批写入和同来源覆盖语义。

### Modified Capabilities

- `dynamic-llm-tools`: 区分业务 Tool 与内部图表规划 Tool，保证内部展示能力不污染业务 Tool 命中日志、调用次数和动态 Tool 管理语义。
- `chat-streaming-cancellation`: 将项目内流式接口统一为类型化 SSE 事件，并定义成功、异常和用户取消时图表事件的发送边界。
- `chat-history-resume`: 助手消息增加图表数据，历史详情和续聊恢复时必须回放已持久化图表。

## Impact

- **后端代码**：`chat/ErpAssistantService`、新增 `chat/client`、`chat/lifecycle`、`chat/dto` 与 `chat/chart/*` 职责包、`controller/ChatController`、`tool/trace`、`conversation/ChatHistoryService`、消息实体/Mapper、`ChatVO` 和 `ConversationVO`。
- **文档导入**：`DocumentLoaderService` 使用本地 WordPiece 分词器二次切分，对超大提取文本和分片数直接拒绝，向量分批写入并对同租户同来源执行覆盖导入；不迁移已存量向量数据。
- **接口**：`GET /api/ask` 的成功响应增加可空 `chart`；`GET /api/ask/stream` 直接返回类型化 SSE；历史消息响应增加可空 `chart`。
- **数据库**：ERP MySQL 的 `a_chat_message` 增加可空图表 JSON 字段；不新增业务表查询，不改变现有 Tool SQL。
- **前端与依赖**：`static/index.html`、`app.js`、`style.css` 增加图表容器、协议解析和历史回放；`static/vendor/` 增加本地 ECharts 核心及词云、水位图扩展，并更新静态资源缓存版本。
- **租户风险**：原始 Tool 结果只允许在当前 `traceId` 和租户上下文中使用，必须在终止路径清理，禁止跨请求或跨租户复用；持久化图表继续通过现有消息租户隔离访问。
- **计费风险**：正常路径继续复用当前问答的 Tool Calling 循环；仅在本轮业务 Tool 漏调用或已有结果但漏规划时分别最多新增一次有界 LLM 请求，所有重试与选择 Token 必须合并到同一助手消息并只执行一次计费扣除；内部规划 Tool 仍不计入业务 Tool 次数。
- **Provider 风险**：DeepSeek、OpenAI 兼容和 Google GenAI 对复杂 Tool Schema 的遵循能力不同，因此模型侧 Schema 收敛为统一的 `type/title` 两字段协议；非法或缺失选择统一降级。
- **兼容性**：项目内前端与后端作为同一制品同步升级流式协议，因此不保留 `v1`/`v2` 分支；历史纯文本 SSE 客户端需要同步适配类型化事件。旧消息的 `chart_spec = NULL` 或非法历史图表继续降级为 `chart = null`，不影响文本历史回放。
