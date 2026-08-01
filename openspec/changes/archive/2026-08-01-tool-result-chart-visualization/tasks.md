## 1. 数据库与对外契约

- [x] 1.1 在空库建表 SQL 中为 `a_chat_message` 增加可空 `chart_spec TEXT` 字段，并在 `ErpDatabaseInitializer` 中实现基于 `information_schema.COLUMNS` 的已有库幂等加列迁移
- [x] 1.2 为 `ErpDatabaseInitializer` 补充空库创建、已有库升级和重复启动不重复执行 DDL 的测试
- [x] 1.3 新增 `ChartVO` 及版本化 `ChartSpec`、`Dataset`、`Dimension`、`ChartOptions`、`ChartSource` record，固定 23 种 type 和安全字段枚举
- [x] 1.4 新增 `ChartSpecCodec`，实现 JSON 序列化/反序列化、`schemaVersion=1.0` 校验、60 KiB 大小限制和非法历史 JSON 降级
- [x] 1.5 扩展 `ChatMessageEntity`、`ChatMessageMapper`、`ChatVO.AskResponse` 和 `ConversationVO.ChatMessageItemResponse` 的可空图表字段，并验证旧消息返回 `chart = null`

## 2. 业务 Tool 结果捕获

- [x] 2.1 在 `chat/chart/model` 包新增不可变 `BusinessToolResult`、`TraceChartContext` 和内部 `ChartPlan` 模型，定义调用顺序、租户/会话边界与规模上限
- [x] 2.2 在 `chat/chart/capture` 包实现 `ToolResultRecorder` 对数组、`rows` 包装对象和单对象结果的安全解析、暂存、读取、第一个有效图表写入和三种终止路径清理
- [x] 2.3 实现每 Tool 50 行、每轮 8 次调用、字符串 500 字符、嵌套深度 8 和过期 context 清理，超限时只禁用图表而不影响 Tool 返回
- [x] 2.4 扩展 `LoggingToolCallback`，在 `code`/`database` Tool 成功后把结果交给 `ToolResultRecorder`，并保持原有调用流水和异常语义
- [x] 2.5 补充结果解析、并发 trace、租户或会话不匹配、成功/异常/取消清理及捕获失败降级的单元测试

## 3. 图表规划校验与后端编译

- [x] 3.1 在 `chat/chart/compile` 包实现 `ChartPlanValidator` 的来源 Tool、调用顺序、字段存在性、数据类型、聚合枚举、转换枚举、单位、排序、limit 和安全 options 白名单校验
- [x] 3.2 实现饼图、环形图、条形图、折线图、面积图、阶梯图和漏斗图的投影、分组聚合、排序及通用 `ChartSpec` 编译
- [x] 3.3 实现散点图、气泡图、雷达图、热力图和平行坐标图的多数值维度校验、大小归一和编码编译
- [x] 3.4 实现直方图 Sturges 分箱与 5～20 分箱限制，以及箱线图五数概括、1.5 IQR 异常值计算
- [x] 3.5 实现旭日图、矩形树图和桑基图的节点/边转换、缺失节点校验、父子环检测和非数值权重拒绝
- [x] 3.6 实现瀑布图累计基线、子弹图实际/目标/范围、甘特图起止时间、仪表盘上下界和水位图 0～1 归一编译
- [x] 3.7 实现词云名称/非负权重编译，并限制词条数量和文本长度
- [x] 3.8 为全部 23 种图表建立后端 fixture 测试，覆盖合法规划、缺失通道、类型错误、单位冲突、BigDecimal 精度和 50 行/32 维度/60 KiB 上限

## 4. LLM 图表规划 Tool 与业务统计隔离

- [x] 4.1 在 `chat/chart/tool` 包实现带显式 JSON Schema 的 `ChartPlanToolCallback`，隔离模型选择输入与内部规划，并返回精简 accepted/reason
- [x] 4.2 在同一 `traceId` 下实现“第一个通过完整校验的规划生效”，重复规划不得覆盖，非法规划不得留下半成品图表
- [x] 4.3 将规划 Tool 在 `AssistantClientProvider.resolveClient()` 中追加到 auto/data 的业务 Tool 快照之外，确保动态 Tool 刷新后仍可用且 knowledge 模式不装配
- [x] 4.4 更新通用 system prompt 和规划 Tool 描述，要求先完成业务查询、再单独规划图表，并给出 23 种类型的通用选择原则
- [x] 4.5 更新业务 Tool 调用统计测试，证明内部规划 Tool 不进入 `a_tool_call_log`、`tool_calls`、`tool_calls_count`、管理端 Tool 列表或动态快照版本
- [x] 4.6 使用 DeepSeek、OpenAI 兼容和 Google GenAI 的 ChatClient 构建路径验证同一规划 Schema，模型不规划或规划非法时只降级为文本

## 5. 非流式问答、持久化与计费收口

- [x] 5.1 将非流式问答内部结果调整为文本加可空 `ChartSpec`，在回答完成后读取本轮首个有效图表并保持现有 token usage 提取
- [x] 5.2 扩展 `ChatHistoryService.saveAssistantMessageAndUpdateStats()`，在同一助手消息 insert 中保存预先序列化的图表 JSON
- [x] 5.3 实现带图表消息保存失败后的无图表单次重试，确保不重复保存消息、回填 Tool 日志或执行计费扣除
- [x] 5.4 更新 `GET /api/ask` 返回 `RespVO<ChatVO.AskResponse>` 的可空 `chart`，保持原有字段和错误码兼容
- [x] 5.5 补充非流式有图、无图、knowledge、规划失败、持久化降级和只扣费一次的 Service/Controller 测试

## 6. 统一类型化 SSE 与取消语义

- [x] 6.1 在 `chat/dto` 定义内部 `ChatStreamFrame`，并定义 `delta`、`chart`、`done`、`error` VO，确保每个事件 data 都是可独立解析的 JSON
- [x] 6.2 重构流式收口为统一 frame 管道，正常完成时按 delta → 可选 chart → done 输出，并使用 `AtomicBoolean finalized` 保证只保存和结算一次
- [x] 6.3 实现流内异常的安全 error 事件，以及用户取消时保留部分文本、保存 `cancelled`、不发送或持久化图表的路径
- [x] 6.4 `GET /api/ask/stream` 直接输出类型化 SSE，不再维护纯文本和结构化事件的并行协议版本
- [x] 6.5 补充类型化事件顺序、多行 Markdown/Unicode、无图 done、流内异常、早期取消、usage 有无及重复 finalize 的测试

## 7. 历史消息与续聊回放

- [x] 7.1 在历史消息查询 Service 中使用 `chat/chart/protocol/ChartSpecCodec` 将 `chart_spec` 转为可空 `ChartSpec`，旧消息或坏 JSON 记录 WARN 后返回 null
- [x] 7.2 验证图表只随现有租户隔离的消息返回，ChatMemory 仍只加载 role/content，不把图表 JSON 注入后续 LLM 上下文
- [x] 7.3 补充成功消息图表回放、旧消息兼容、cancelled/error 无图表、跨租户拒绝和不重新调用 LLM/业务 Tool 的测试

## 8. 本地 ECharts 资源与通用适配器

- [x] 8.1 锁定兼容的 Apache ECharts 6.x、官方 word-cloud/liquid-fill custom series 版本，下载浏览器 auto 构建到 `static/vendor/`，记录许可证、NOTICE、版本和 SHA-256
- [x] 8.2 新增零构建 `static/chart-adapter.js`，实现协议版本/type 白名单、实例注册、render/dispose/resize API、主题变量读取和安全固定 tooltip
- [x] 8.3 实现 pie/donut/bar/line/area/step/funnel/waterfall/bullet/gauge 的 ECharts option 适配
- [x] 8.4 实现 radar/scatter/bubble/histogram/boxplot/heatmap/parallel 的 ECharts option 适配
- [x] 8.5 实现 sunburst/treemap/sankey/gantt/word-cloud/liquid-fill 的 tree、nodes/links、本地水平时间范围和官方 custom series 适配
- [x] 8.6 为 23 种前端 `ChartSpec` fixture 验证 option 构造、未知版本/type 降级、恶意文本不执行和扩展缺失不影响页面

## 9. 聊天前端、历史前端与样式

- [x] 9.1 更新 `index.html` 按 ECharts → word-cloud/liquid-fill custom series → `chart-adapter.js` → `app.js` 顺序加载本地脚本并递增所有相关缓存版本
- [x] 9.2 将 `sendQuestion()` 切换为统一类型化 SSE，实现标准 event/data JSON 解析和 delta/chart/done/error 分派，保留停止回答交互
- [x] 9.3 调整消息 DOM 返回结构，在助手 Markdown 下方安全创建单个图表卡片，复制按钮仍只复制回答文本
- [x] 9.4 在历史详情和 `continueConversation()` 中复用统一消息图表渲染函数，禁止把完整 ChartSpec 拼入 `innerHTML` 或 HTML attribute
- [x] 9.5 在新建对话、切换会话、清空列表和窗口尺寸变化时调用 dispose/resize，并验证不会遗留 Canvas 实例
- [x] 9.6 更新 `style.css` 使用现有 CSS 变量实现桌面/窄屏图表卡片尺寸、间距、边框和错误降级布局，并递增缓存版本
- [x] 9.7 扩展 `StaticFrontendContractTest`，验证统一类型化 SSE、本地资源、无 CDN、缓存版本、历史回放入口和危险 ECharts 配置未开放

## 10. 综合验证与交付门禁

- [x] 10.1 运行图表相关定向单元测试，覆盖 compiler、planner、recorder、持久化、Controller、SSE 和前端契约
- [x] 10.2 运行完整 `mvn clean package`，确认原有 Tool、RAG、会话、计费、动态 Tool 管理和取消测试无回归
- [x] 10.3 手工验证 23 种 fixture 的桌面与窄屏展示、Markdown 相邻布局、tooltip、滚动、停止回答、新建对话、历史详情和续聊
- [x] 10.4 运行 `openspec validate tool-result-chart-visualization --strict`，确保 proposal、design、四份 delta specs 和 tasks 全部通过严格校验

## 11. 职责分包与流式接口收敛

- [x] 11.1 将 `chat.chart` 按 `model`、`capture`、`compile`、`protocol`、`tool` 职责迁移到下一层子包，并同步测试包与全部引用
- [x] 11.2 将 `ChatAnswerResult`、`ChatStreamFrame`、`DocSnippet` 和助手消息保存结果 record 迁入 `chat.dto`
- [x] 11.3 提取 `AssistantClientProvider`，集中管理多 Provider 路由、Tool 装配、System Prompt 和 Client 缓存
- [x] 11.4 提取 `AssistantLifecycleService`，集中管理会话准备、持久化、计费、Tool 流水与流式终止收口，并增加 Bean 构造器依赖无环测试
- [x] 11.5 移除前后端一体场景下的 v1/v2 分支，`/api/ask/stream` 与 `app.js` 直接使用最新类型化 SSE，并保留旧历史消息 `chart = null` 降级
- [x] 11.6 运行定向测试、完整 `mvn clean package`、差异检查和 OpenSpec 严格校验，确认拆分未改变原问答、租户、计费和历史逻辑

## 12. 审查问题修复

- [x] 12.1 将会话状态查询、历史消息读取和软删除统一为 `ent_code + user_id + conversationId` 所有权校验，并补充同租户跨用户拒绝测试
- [x] 12.2 为各图表类型增加 transform 兼容白名单，拒绝编译器不会执行的转换
- [x] 12.3 水位图显式上下界范围外的业务值改为拒绝编译，不再静默截断
- [x] 12.4 前端条形、折线、面积、阶梯、散点和气泡图按可选 `series` 通道拆分系列并补充 fixture
- [x] 12.5 图表初始化后渲染失败时释放临时实例，页面移除失败卡片并保留助手文本
- [x] 12.6 递增静态资源缓存版本并清理 `AssistantClientProvider` 新增文本块中的尾随空白
- [x] 12.7 运行 120 项完整 Maven 测试、25 个前端 option fixture、差异检查和 OpenSpec 严格校验

## 13. 二次审查问题修复

- [x] 13.1 修复无 `series` 通道的直角坐标图对重复类别只保留第一条数据的问题，并新增逐行保留 fixture
- [x] 13.2 修复常量直方图最后分箱起点大于终点的问题，并验证分箱边界和总计数
- [x] 13.3 为仪表盘补齐后端默认 `0` 到 `100` 范围校验，并验证缺省 options 的越界拒绝
- [x] 13.4 校验子弹图 actual、target 和 range 的显式单位一致性，并补充单位冲突测试
- [x] 13.5 将 `plan_chart_visualization` 设为系统保留名称，在动态 Tool 配置和 ChatClient 最终装配两层拒绝冲突
- [x] 13.6 移除与功能无关且可能产生误导审核标记的 `.gitmessage`
- [x] 13.7 运行 125 项完整 Maven 测试、26 个前端 option fixture、差异检查和 OpenSpec 严格校验

## 14. 三次审查问题修复

- [x] 14.1 交叉校验 `transform.type`、`groupBy` 和字段 `aggregate`，拒绝被忽略或退化为分组第一行的聚合声明
- [x] 14.2 为桑基图增加完整有向环检测，并补充多节点循环回归测试
- [x] 14.3 多系列直角坐标图按同类目出现顺序扩展坐标槽，保留同系列重复类目的全部业务数据
- [x] 14.4 仪表盘编译结果强制为单个业务指标，并补充多行拒绝测试
- [x] 14.5 统一数值通道判断，确保子弹图 target 的显式单位参与 `options.unit` 冲突校验
- [x] 14.6 运行 129 项完整 Maven 测试、27 个前端 option fixture、差异检查和 OpenSpec 严格校验

## 15. 图表类型枚举规范

- [x] 15.1 新增统一 `ChartVO.ChartType` 枚举，并将 `ChartPlan.type`、`ChartSpec.type`、校验映射和编译分支从裸字符串迁移为枚举
- [x] 15.2 由 `ChartType` 生成 LLM Tool Schema 类型清单，并验证 Fastjson 历史数据解析与 Jackson 对外字符串协议兼容
- [x] 15.3 运行完整 Maven 测试、27 个前端 option fixture、差异检查和 OpenSpec 严格校验

## 16. 图表规划 Tool 描述可读性

- [x] 16.1 将 `ChartPlanToolCallback.DESCRIPTION` 按用途、调用条件、填写规则和图表选择分段，并增加结构化描述契约测试
- [x] 16.2 运行完整 Maven 测试、差异检查和 OpenSpec 严格校验

## 17. 四次审查问题修复

- [x] 17.1 约束 `sortBy` 必须在编译结果中可执行，并为直方图和箱线图定义明确排序规则
- [x] 17.2 拒绝雷达图负指标和水位图多行数据，避免无效上界及多指标静默丢失
- [x] 17.3 限制语义通道绑定基数，并校验调用顺序选中 Tool 名称集合与声明来源完全一致
- [x] 17.4 甘特图渲染可选进度，并使用完整原值区分系列和桑基节点内部标识
- [x] 17.5 前端暂存 `chart` 直到收到 `done`，流结束缺少 `done` 时按提前中断处理
- [x] 17.6 在 `ChartPlanToolCallback` 反序列化前递归拒绝 Schema 外字段
- [x] 17.7 运行定向测试、完整 Maven 测试、前端 fixture、差异检查和 OpenSpec 严格校验

## 18. 五次审查问题修复

- [x] 18.1 使用跨分片状态规范化 SSE 换行，修复 CRLF 在网络边界拆分时丢失类型化事件的问题
- [x] 18.2 拒绝普通数值通道空值，允许 `count` 聚合统计字符串业务标识，并避免热力图把历史空值转换为零
- [x] 18.3 约束 `groupBy` 仅引用聚合规划中的已绑定字段，并在聚合后再次投影最终数据行
- [x] 18.4 兼容 MySQL DATETIME 甘特图来源时间并统一输出 ISO-8601 字符串
- [x] 18.5 强化 `ChartSpecCodec` 对类型通道、字段基数、维度引用、数据行和值类型的历史数据校验
- [x] 18.6 为 Tool 原始 JSON 增加解析前字节、对象宽度、集合宽度和总结构节点资源上限
- [x] 18.7 补充后端边界、前端 CRLF 分片和热力图空值回归测试，并递增静态资源缓存版本
- [x] 18.8 运行完整 Maven 测试、前端 fixture、差异检查、Git 完整性检查和 OpenSpec 严格校验

## 19. 六次审查问题修复

- [x] 19.1 支持热力图使用字符串类别坐标，并增加后端编译与前端适配共享 fixture
- [x] 19.2 在缺少可信语义和单位元数据时限制每个图表规划只能选择一个业务 Tool 结果
- [x] 19.3 统一规划与历史 `ChartSpec` 的 options 白名单，严格校验空值和雷达指标上界
- [x] 19.4 为气泡图、水位图和雷达图派生字段分配不与业务字段碰撞的稳定键
- [x] 19.5 补充单来源、多类历史协议和派生字段碰撞回归测试
- [x] 19.6 运行完整 Maven 测试、前端 fixture、差异检查和 OpenSpec 严格校验

## 20. 七次审查问题修复

- [x] 20.1 前端统一通过 `encoding` 读取编译器派生字段，并补充前后端共享碰撞 fixture
- [x] 20.2 强化历史 `ChartSpec` 语义通道类型和日期格式校验
- [x] 20.3 为图表规划 Tool 输入增加解析前字节、嵌套深度、结构宽度和节点总量限制，并改为单次解析
- [x] 20.4 递增图表适配器缓存版本并补充后端、前端边界回归测试
- [x] 20.5 运行 148 项完整 Maven 测试、33 个前端 fixture、差异检查和 OpenSpec 严格校验

## 21. 八次审查问题修复

- [x] 21.1 仅在甘特图 `start`/`end` 通道识别日期形字符串，普通字符串类目保持文本类型，并补充混合类目回归测试
- [x] 21.2 运行完整 Maven 测试、前端 fixture、差异检查和 OpenSpec 严格校验

## 22. 运行反馈问题修复

- [x] 22.1 强化系统提示词，中文问题全程使用中文回答，禁止暴露 Tool、函数和数据库内部标识，并避免动态与代码 Tool 重复查询
- [x] 22.2 将两行及以上且包含数值字段的业务结果明确为必须规划图表，增强规划 Tool 描述，并在模型漏规划时记录安全诊断日志
- [x] 22.3 补充提示词和规划 Tool 描述回归测试，运行完整 Maven、前端 fixture、差异检查和 OpenSpec 严格校验

## 23. 图表规划校验失败修复

- [x] 23.1 将规划校验异常转换为不含业务数据的安全分类原因，失败候选不占用有效图表名额
- [x] 23.2 强化系统提示词和规划 Tool 描述，禁止向用户展示内部失败和降级过程
- [x] 23.3 补充规划失败安全降级测试，运行完整 Maven、前端 fixture、差异检查和 OpenSpec 严格校验

## 24. 单次问答图表数量语义澄清

- [x] 24.1 明确每次问答返回最多一个图表，同一会话不同问答轮次不限制累计图表数量
- [x] 24.2 补充同一会话不同 `traceId` 分别接受图表的回归测试，并运行定向测试、差异检查和 OpenSpec 严格校验

## 25. 内部执行旁白隐藏

- [x] 25.1 新增最终答案净化器和边界协议，非流式回答及新持久化消息只保留最终业务答案
- [x] 25.2 流式链路在最终答案边界前抑制 `delta`，兼容边界跨分片、无边界安全前缀判定及完成阶段兜底净化
- [x] 25.3 历史读取兼容净化旧助手消息，保持用户消息原文，并强化系统提示词禁止输出 Tool Calling 过程
- [x] 25.4 补充净化器、非流式、流式、历史和 Bean 依赖测试，运行完整 Maven、前端 fixture、差异检查和 OpenSpec 严格校验

## 26. LLM 图表职责收敛与后端自动规划

- [x] 26.1 将内部图表 Tool Schema 收敛为 `type/title` 两字段，并同步系统提示词和结构化 Tool 描述
- [x] 26.2 新增 `ChartPlanFactory`，根据结构化业务结果自动选择单一来源、字段绑定、聚合转换、TOP N 限制和安全展示选项
- [x] 26.3 图表编译逐一尝试本轮候选来源，补充销售数量自动绑定与重复产品聚合、其他结果回退、不可满足时降级及 23 种类型回归测试
- [x] 26.4 更新根节点空父级兼容、Bean 依赖和多 Provider Schema 测试，运行完整 Maven、前端 fixture、差异检查和 OpenSpec 严格校验

## 27. 纯分类业务数据图表生成修复

- [x] 27.1 扩展 `ChartPlanFactory`，对没有数值列但存在可比较分类的业务结果使用独立非空字段执行可信 `count` 聚合
- [x] 27.2 补充售后工单状态饼图和分类计数条形图回归测试，并同步系统提示词与图表规划 Tool 描述
- [x] 27.3 运行图表定向测试、完整 Maven 测试、前端 fixture、差异检查和 OpenSpec 严格校验

## 28. 长会话当前轮数据与图表规划可靠性修复

- [x] 28.1 新增当前轮业务数据守卫，历史回答仅用于理解上下文；业务数据问题未产生当前轮 Tool 结果时丢弃首次回答并最多重试一次，流式链路不得发送未验证首次回答
- [x] 28.2 新增独立图表选择服务，已有结构化业务结果但主调用漏规划时由当前模型只补选一次 `type/title`，后端继续自动绑定、编译和安全校验
- [x] 28.3 合并首次调用、业务查询重试和图表选择的 Token 用量，保持助手消息只保存一次、计费只扣除一次、内部选择不计入业务 Tool 流水
- [x] 28.4 区分“本轮未调用业务 Tool”“业务 Tool 空结果”“已有数据但类型不兼容”和“模型未提交规划”的中文诊断日志
- [x] 28.5 补充长会话复用历史表格、非流式与流式有界重试、规划兜底、knowledge 不重试和 Bean 依赖无环测试
- [x] 28.6 运行定向测试、完整 Maven 测试、前端 fixture、差异检查和 OpenSpec 严格校验

## 29. 当前轮业务守卫流式输出恢复

- [x] 29.1 将流式业务数据守卫从整流聚合改为确认前缀门控，捕获当前轮结果后丢弃 Tool 前旁白并从确认分片开始透传
- [x] 29.2 在不缓存完整重试流的前提下合并首次与重试 Token，并保持未验证首次回答不泄漏
- [x] 29.3 补充上游未完成即可收到响应的回归测试，运行定向测试、完整 Maven 测试、前端 fixture、差异检查和 OpenSpec 严格校验

## 30. 英文内部执行旁白泄漏修复

- [x] 30.1 调整业务数据流式门控，确认当前轮结果时只发送当前及后续响应，不释放确认前的查询和规划旁白
- [x] 30.2 扩展无最终答案边界的兼容净化，安全移除明确的英文查询和图表规划前缀并保留中文业务答案
- [x] 30.3 补充英文旁白连续拼接、流式完成兜底和 Tool 前响应不泄漏测试，运行定向测试、完整 Maven、前端 fixture、差异检查和 OpenSpec 严格校验

## 31. 无最终答案边界的实时流式恢复

- [x] 31.1 将流式净化从无边界整段缓存改为安全前缀判定，确认业务正文后立即释放并透传后续分片
- [x] 31.2 保持跨分片最终边界、中文与英文执行旁白抑制以及无法判定内容的完成阶段兜底
- [x] 31.3 补充无边界中文正文逐分片输出和英文旁白后正文实时恢复测试，运行定向测试、完整 Maven、前端 fixture、差异检查和 OpenSpec 严格校验

## 32. 首次图表补选稳定性修复

- [x] 32.1 将补选模型调用收敛为零随机度的单次原始响应，并兼容唯一 JSON 对象的 Markdown 代码围栏
- [x] 32.2 严格拒绝多个 JSON 对象和 Schema 外字段，强化主回答不得提前宣称图表已生成
- [x] 32.3 补充补选格式兼容与安全拒绝测试，运行定向测试、完整 Maven、前端 fixture、差异检查和 OpenSpec 严格校验

## 33. 文档导入与依赖检查修复

- [x] 33.1 为文档提取文本、最终分片数和向量批次增加资源上限，批次失败时清理本次残留数据
- [x] 33.2 使用实际 ONNX WordPiece 分词器校验并二次切分超过 128 token 的文档分片
- [x] 33.3 同租户同来源文档重新导入时覆盖旧向量，不累加重复分片
- [x] 33.4 补全新增 Spring Bean 的构造器依赖无环检查，运行定向测试、完整 Maven 测试和 OpenSpec 严格校验

## 34. 九次审查问题修复

- [x] 34.1 扩展自动模式英文直接业务数据问题识别，覆盖 `What are the sales orders`、`Give me sales orders` 并避免误判产品知识问题
- [x] 34.2 为直角坐标图自动绑定标题明确指向的可比较系列字段，聚合时按横轴和系列联合分组
- [x] 34.3 为甘特图自动绑定 0～1 的可选进度字段，并由前端使用水平时间范围条渲染任务和完成进度
- [x] 34.4 流式错误事件和连接异常复用当前助手气泡展示，不新增重复或空白助手消息
- [x] 34.5 更新项目上下文中的 Spring Boot、Spring AI 和持久层事实，移除不再使用的 bar-range 浏览器依赖
- [x] 34.6 运行定向测试、完整 Maven 测试、前端 fixture、差异检查和 OpenSpec 严格校验

## 35. 十次审查问题修复

- [x] 35.1 收窄最终答案兼容净化条件，保留包含内部协议同名词的合法技术说明和业务建议
- [x] 35.2 从图表数值指标候选中排除订单号、工单号等数值型业务标识，并补充回归测试
- [x] 35.3 支持标题通过字段名或常用业务别名识别自定义系列维度，并确保时间字段优先作为趋势横轴
- [x] 35.4 运行定向 Maven 测试、差异检查和 OpenSpec 严格校验

## 36. 十一次审查问题修复

- [x] 36.1 流式取消或异常发生在最终答案边界标记中间时丢弃不完整协议片段，并补充全部前缀位置回归测试
- [x] 36.2 运行定向 Maven 测试、完整 Maven 测试、差异检查和 OpenSpec 严格校验

## 37. 十二次审查问题修复

- [x] 37.1 流式旁白后出现跨分片最终答案标记时继续暂存，取消或异常时删除末尾不完整协议片段
- [x] 37.2 将 Tool 原始 JSON 的结构化行与空结果状态合并为单次有界解析结果，超出字节预算后不得再次解析
- [x] 37.3 补充残缺标记和超限空 JSON 回归测试，运行定向测试、完整 Maven 测试、差异检查和 OpenSpec 严格校验
