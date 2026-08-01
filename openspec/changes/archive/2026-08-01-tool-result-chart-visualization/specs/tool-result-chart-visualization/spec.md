## ADDED Requirements

### Requirement: 成功业务 Tool 结果必须进入图表判定

系统 SHALL 在 auto 或 data 模式的一轮问答中捕获 `code`、`database` 类型业务 Tool 的成功返回结果，并以 `traceId`、`ent_code` 和 `conversationId` 为边界暂存，供同轮 LLM 选择图表表达。系统 MUST NOT 为 knowledge 模式、失败 Tool、空结果或不具备可视化意义的结果生成图表。

#### Scenario: 成功非空业务结果进入图表规划
- **WHEN** auto 或 data 模式中的代码 Tool 或动态数据库 Tool 成功返回非空业务数据
- **THEN** 系统 MUST 将该结果关联到当前 `traceId` 和租户上下文
- **AND** 系统 MUST 允许同轮 LLM 基于该真实结果选择图表类型和标题

#### Scenario: 历史回答不得替代当前轮业务查询
- **WHEN** auto 或 data 模式的当前问题要求查询 ERP 业务数据
- **THEN** 历史用户消息和助手回答 MUST 只用于理解业务主体和指代
- **AND** 系统 MUST NOT 将历史 Markdown 表格、历史助手数字或前一 `traceId` 的暂存结果当作当前轮业务数据
- **AND** 当前轮 MUST 通过业务 Tool 取得结构化结果后才能生成业务数字回答和图表

#### Scenario: 当前轮漏调用业务 Tool 时有界重试
- **WHEN** 系统识别当前问题要求 ERP 业务数据，但首次模型调用结束后当前 `traceId` 没有业务 Tool 结果
- **THEN** 系统 MUST 丢弃首次调用产生的未验证业务回答
- **AND** 系统 MUST 使用相同模型、租户、会话和 `traceId` 最多重试一次业务查询
- **AND** 重试提示 MUST 明确禁止复用历史业务数字
- **AND** 首次调用与重试的 Token MUST 合并到同一助手消息并只扣费一次
- **AND** knowledge 模式 MUST NOT 触发该重试

#### Scenario: 英文直接业务数据问题进入当前轮守卫
- **WHEN** auto 模式用户使用 `What are the sales orders`、`Give me sales orders` 等直接问句或祈使句请求 ERP 业务记录
- **THEN** 系统 MUST 将其识别为当前轮业务数据问题
- **AND** 当前轮没有业务 Tool 结果时 MUST 执行既有有界查询重试
- **AND** 产品安装、产品规格等知识说明问题 MUST NOT 因包含产品词被误判为业务数据查询

#### Scenario: 多行可比较业务结果必须规划图表
- **WHEN** 成功业务 Tool 返回两行及以上数据，且至少包含一个数值字段或可按状态、类型等字段分类计数
- **THEN** LLM MUST 将该结果判定为适合可视化
- **AND** LLM MUST 在最终回答前单独调用内部图表规划 Tool
- **AND** 最终回答已经使用 Markdown 表格时也 MUST NOT 省略图表规划
- **AND** 系统检测到已捕获业务结果但没有有效图表时 MUST 记录不包含原始业务行的诊断日志

#### Scenario: 空结果或失败结果不生成图表
- **WHEN** 本轮所有业务 Tool 均执行失败、返回空集合或不包含可用于比较、趋势、分布、层级、关系、进度或指标展示的数据
- **THEN** 系统 MUST 继续返回文本回答
- **AND** 响应中的图表字段 MUST 为空

#### Scenario: knowledge 模式不生成业务图表
- **WHEN** 用户以 knowledge 模式发起问答
- **THEN** 系统 MUST NOT 捕获业务 Tool 结果或暴露图表规划 Tool
- **AND** 响应中的图表字段 MUST 为空

#### Scenario: 原始 Tool 结果按请求生命周期清理
- **WHEN** 问答正常完成、发生异常或被用户取消
- **THEN** 系统 MUST 清理当前 `traceId` 下暂存的原始 Tool 结果和未持久化图表
- **AND** 后续请求 MUST NOT 读取到其他请求或其他租户的暂存结果

#### Scenario: 原始 Tool 结果超出结构资源预算
- **WHEN** 单次业务 Tool 结果超过原始 UTF-8 字节、对象字段、集合元素、总结构节点或嵌套深度上限
- **THEN** 系统 MUST 在进入图表上下文前拒绝该结果
- **AND** 系统 MUST NOT 因解析或复制超大结构影响原有 Tool 返回和文本回答

### Requirement: 图表类型和标题由 LLM 选择，完整规划由后端生成

系统 SHALL 通过内部图表选择 Tool 让当前问答使用的 LLM 只选择图表类型和业务标题。LLM MUST NOT 提交来源 Tool、字段映射、转换、安全选项或最终业务数值；完整内部 `ChartPlan` 和最终 `ChartSpec` MUST 由后端根据被捕获的真实 Tool 结果生成。

#### Scenario: LLM 只提交图表类型和标题
- **WHEN** LLM 判断成功业务 Tool 结果适合可视化
- **THEN** LLM MUST 调用内部图表选择 Tool
- **AND** 调用参数 MUST 只包含固定枚举 `type` 和 1～120 字符的安全纯文本 `title`
- **AND** 调用参数 MUST NOT 包含来源 Tool、调用顺序、字段名、语义通道、转换、单位、展示选项、ECharts option 或业务数值

#### Scenario: 已有业务结果但模型漏规划时补做结构化选择
- **WHEN** 当前轮已经捕获非空业务 Tool 结果但主模型调用没有生成有效图表
- **THEN** 后端 MUST 使用当前请求选择的同一模型最多补做一次结构化 `type/title` 选择
- **AND** 选择请求 MUST NOT 暴露业务 Tool，也 MUST NOT返回面向用户的文本
- **AND** 后端 MUST 将选择结果交给既有 `ChartPlanFactory`、校验器和编译器
- **AND** 后端 MUST NOT 擅自替换 LLM 选择的图表类型
- **AND** 选择请求的 Token MUST 合并到本轮消息与单次计费

#### Scenario: 补选响应格式存在无害代码围栏
- **WHEN** 图表补选模型返回唯一 `type/title` JSON 对象，但 Provider 在对象外增加 Markdown JSON 代码围栏
- **THEN** 后端 MUST 提取该唯一 JSON 对象并继续执行既有图表规划与完整安全校验
- **AND** 顶层字段不是恰好 `type/title`、包含多个 JSON 对象或超过资源预算时 MUST 拒绝该选择
- **AND** 兼容解析 MUST NOT 改变 LLM 选择的图表类型和标题

#### Scenario: 用户回答不得提前宣称图表已生成
- **WHEN** 当前轮业务回答正在生成且最终 `ChartSpec` 尚未确定
- **THEN** 主回答 MUST 只描述业务数据和结论
- **AND** 主回答 MUST NOT 宣称图表已经生成、展示或渲染
- **AND** 页面是否展示图表 MUST 以最终收到的有效 `ChartSpec` 为准

#### Scenario: 后端自动生成完整内部规划
- **WHEN** 系统收到合法的图表类型和标题
- **THEN** 后端 MUST 从本轮已捕获结果中识别共同标量、数值、非负数值、日期和文本字段
- **AND** 后端 MUST 结合标题业务语义、字段顺序和类型约束自动选择一个来源结果及字段绑定
- **AND** 后端 MUST 自动生成图表类型允许的转换、排序、行数限制和安全展示选项
- **AND** 最终业务数值 MUST 仅来自被选中的真实 Tool 结果

#### Scenario: 数值型业务标识不得作为图表指标
- **WHEN** 结构化结果同时包含数值型订单号、工单号、编码或其他标识字段和真实业务度量字段
- **THEN** 后端 MUST 从数值指标候选中排除这些业务标识字段
- **AND** 后端 MUST 优先使用金额、数量、比例等真实度量字段生成数值通道
- **AND** 业务标识 MAY 继续作为分类计数的记录标识或非指标类别字段

#### Scenario: 重复类别由后端自动聚合
- **WHEN** 条形图、折线图、面积图、阶梯图或热力图的自动分组字段存在重复组合
- **THEN** 后端 MUST 生成受控 `aggregate` 转换
- **AND** 普通数量和金额指标 MUST 使用 `sum`
- **AND** 比率、百分比和平均值语义指标 MUST 使用 `avg`
- **AND** 后端 MUST NOT 使用分组第一行替代聚合结果

#### Scenario: 纯分类业务结果由后端按记录数聚合
- **WHEN** 多行结构化业务结果没有数值列，但包含状态、类型或其他可比较分类字段
- **AND** LLM 选择饼图、环形图、条形图、漏斗图或词云图
- **THEN** 后端 MUST 从标题语义选择分类字段
- **AND** 后端 MUST 使用每行均非空且独立于分类字段的业务字段执行 `count` 聚合
- **AND** 计数 MUST 仅来源于当前捕获结果的真实记录数
- **AND** 后端 MUST NOT 使用 LLM 回答文本中推导的数量或百分比

#### Scenario: 标题中的排名限制由后端执行
- **WHEN** 图表标题包含 `TOP N` 或“前 N”且 N 为正整数
- **THEN** 后端 MUST 将结果限制为最多 N 行
- **AND** N 超过协议上限时 MUST 收敛到 50 行
- **AND** 不包含排名限制时 MUST 继续遵守 50 行协议上限

#### Scenario: 后端尝试全部兼容来源后才降级
- **WHEN** 本轮包含多个已捕获业务 Tool 结果
- **THEN** 后端 MUST 按标题相关性、结构兼容性和调用顺序生成候选内部规划
- **AND** 当前候选未通过完整编译校验时 MUST 继续尝试其他候选
- **AND** 只有所有候选都无法满足 LLM 所选图表类型时才返回 `chart = null`
- **AND** 系统 MUST NOT 改用未经 LLM 选择的其他图表类型

#### Scenario: 后端规划继续执行完整安全校验
- **WHEN** 后端生成来源、字段绑定、转换和展示选项
- **THEN** 系统 MUST 继续校验来源调用顺序、字段存在性、字段类型、聚合规则、单位兼容性、转换白名单和安全选项
- **AND** 系统 MUST NOT 向前端返回部分或未经校验的图表
- **AND** 文本回答 MUST 在图表降级时保持可用

#### Scenario: 运行时拒绝两字段 Schema 外输入
- **WHEN** Provider 提交 `type/title` 之外的字段、非法类型、危险标题或超过资源预算的选择 JSON
- **THEN** 系统 MUST 在生成内部 `ChartPlan` 前拒绝该输入
- **AND** 原始字节和嵌套深度 MUST 在 JSON 解析前检查
- **AND** 同一选择原始文本 MUST 只解析一次
- **AND** `additionalProperties=false` MUST NOT 仅依赖 Provider 侧执行

#### Scenario: 多 Provider 使用相同选择协议
- **WHEN** 用户选择 DeepSeek、OpenAI 兼容或 Google GenAI 模型进行 auto 或 data 模式问答
- **THEN** 系统 MUST 继续通过 `ModelRegistry` 选择 ChatModel
- **AND** 各 Provider MUST 使用同一份 `type/title` 两字段 Schema 和同一套后端自动规划规则
- **AND** 某 Provider 未产生合法选择时 MUST 降级为纯文本，不得由后端擅自选择图表类型

### Requirement: 每轮问答最多返回一个图表

系统 SHALL 保证每轮用户提问及其对应助手回答最多返回一个有效图表。一个 `conversationId` 下的不同问答轮次 MUST 能分别保存和展示各自的单个图表，系统 MUST NOT 限制整个会话累计包含的图表数量。

#### Scenario: 同轮只有一个有效规划
- **WHEN** LLM 在一轮问答中提交一个合法图表规划
- **THEN** 系统 MUST 最多生成并返回一个 `ChartSpec`

#### Scenario: 同轮重复提交图表规划
- **WHEN** LLM 在同一 `traceId` 下多次调用图表规划 Tool
- **THEN** 系统 MUST 只接受第一个通过完整校验的规划
- **AND** 后续规划 MUST NOT 覆盖已经接受的 `ChartSpec`

#### Scenario: 同一会话的不同问答轮次分别返回图表
- **WHEN** 同一 `conversationId` 下的不同用户提问分别创建独立 `traceId`
- **THEN** 每个 `traceId` MUST 能独立接受并返回一个有效 `ChartSpec`
- **AND** 前一轮已经返回图表 MUST NOT 阻止后一轮生成图表

#### Scenario: 多个 Tool 结果选择主数据源
- **WHEN** 一轮问答成功调用多个业务 Tool
- **THEN** 后端 MUST 根据 LLM 标题、字段业务语义、数据结构兼容性和调用顺序选择一份结果作为图表主数据源
- **AND** 文本回答 MAY 继续包含其他 Tool 的结果

#### Scenario: 后端规划只使用一个来源结果
- **WHEN** 后端从本轮一个或多个业务 Tool 结果生成内部规划
- **THEN** 每个候选内部规划 MUST 精确绑定一个来源 Tool 名称和一个完成顺序
- **AND** 系统 MUST NOT 合并不同 Tool 结果或推断不同来源的单位兼容

### Requirement: 通用 ChartSpec 必须版本化且与渲染库解耦

系统 SHALL 通过 `ChartSpec` 向前端返回图表，顶层 MUST 包含 `schemaVersion`、`chartId`、`type`、`title`、`dataset`、`encoding`、`options` 和 `source`。`ChartSpec` MUST 是纯 JSON 数据协议，MUST NOT 直接暴露 ECharts option 或可执行代码。

#### Scenario: 返回通用数据集结构
- **WHEN** 后端成功编译图表
- **THEN** `dataset` MUST 包含维度定义 `dimensions` 和数据行 `rows`
- **AND** 每个维度 MUST 包含稳定字段键 `key`、展示名 `label` 和数据类型 `dataType`
- **AND** `encoding` MUST 使用语义通道到字段键列表的映射描述图形含义
- **AND** `source` MUST 记录来源 Tool 名称列表，但 MUST NOT 暴露 SQL、租户编码或 Tool 原始入参

#### Scenario: 历史图表协议必须完整校验
- **WHEN** 系统读取已持久化 `chart_spec`
- **THEN** 系统 MUST 按图表类型校验必需和可选语义通道、通道字段数量、维度引用、数据行字段和值类型
- **AND** 数值语义通道 MUST 引用 `number` 维度，甘特图开始和结束通道 MUST 引用 `date` 或 `datetime` 维度
- **AND** 历史日期字符串 MUST 是可解析的 ISO-8601 日期或日期时间
- **AND** 除历史热力图数值通道外，必需数据字段 MUST NOT 为空
- **AND** 雷达图指标和上界 MUST 一一对应且上界不能小于指标值
- **AND** 历史 `options` MUST 通过与新规划相同的安全值域白名单
- **AND** 缺失通道、未知字段、类型不一致或空数据集 MUST 按非法历史图表降级
- **AND** 助手文本历史回放 MUST 保持可用

#### Scenario: options 仅允许安全白名单
- **WHEN** `ChartSpec` 包含图表展示选项
- **THEN** `options` MUST 仅包含后端白名单允许的方向、堆叠、平滑、阶梯方式、分箱数、最小值、最大值、单位、排序和标签显示等声明式值
- **AND** `options` MUST NOT 包含函数、HTML、URL、CSS、正则表达式或任意嵌套渲染器配置

#### Scenario: 前端遇到未知协议版本
- **WHEN** 前端收到不支持的 `schemaVersion`
- **THEN** 前端 MUST 跳过图表渲染并保留文本回答
- **AND** 页面 MUST NOT 因未知版本抛出未处理异常

### Requirement: 系统必须支持至少二十三种图表类型

系统 SHALL 使用单一后端枚举规范 `ChartPlan.type` 与 `ChartSpec.type`，并支持以下协议编码：`donut`、`sunburst`、`bar`、`waterfall`、`bullet`、`area`、`step`、`radar`、`scatter`、`bubble`、`histogram`、`boxplot`、`heatmap`、`sankey`、`treemap`、`gantt`、`funnel`、`word-cloud`、`gauge`、`liquid-fill`、`parallel`、`line`、`pie`。每种类型 MUST 定义必需的语义通道和字段类型校验。

#### Scenario: 后端统一使用图表类型枚举
- **WHEN** 系统解析 LLM 图表规划、编译图表或返回 `ChartSpec`
- **THEN** 后端 MUST 使用同一个图表类型枚举执行分支和白名单判断
- **AND** LLM Tool Schema 的类型清单 MUST 来源于该枚举
- **AND** 系统 MUST 继续以原有小写连字符字符串与前端交互

#### Scenario: 历史字符串图表类型保持兼容
- **WHEN** 系统读取包含既有 `bar`、`word-cloud` 或其他受支持字符串类型的历史 `chart_spec`
- **THEN** 系统 MUST 将字符串解析为对应图表类型枚举
- **AND** 再次返回或持久化时 MUST 保持原协议字符串不变
- **AND** 未知类型 MUST 继续按非法历史图表降级

#### Scenario: 类别和时序图使用匹配通道
- **WHEN** LLM 选择 `bar`、`area`、`step`、`line`、`pie`、`donut` 或 `funnel`
- **THEN** 后端 MUST 要求可作为类别或时间轴的字段以及至少一个真实数值字段或后端可信计数字段
- **AND** 前端 MUST 根据 `type` 使用对应图形语义渲染

#### Scenario: 普通字符串类目保留文本语义
- **WHEN** 非甘特时间通道的同一字符串字段同时包含可解析的日期形文本和普通文本
- **THEN** 后端 MUST 将这些值统一视为 `string`，并在 `ChartSpec` 中声明字符串维度
- **AND** 后端 MUST NOT 因部分字符串形似日期而拒绝合法图表
- **AND** 只有甘特图 `start` 和 `end` 通道 MAY 将日期形字符串识别为日期类型

#### Scenario: 可选系列通道拆分多个数据系列
- **WHEN** `bar`、`area`、`step`、`line`、`scatter` 或 `bubble` 的合法 `ChartSpec` 包含 `series` 通道
- **THEN** 前端 MUST 按系列字段值生成独立 ECharts series
- **AND** 类别或时序图 MUST 在统一坐标轴上对齐各系列数据
- **AND** 缺少 `series` 通道的历史图表 MUST 保持原单系列渲染结果

#### Scenario: 后端自动绑定标题明确要求的系列维度
- **WHEN** 条形图、折线图、面积图、阶梯图、散点图或气泡图标题明确要求按产品、客户或其他分类维度对比，且当前结构化结果存在 2～12 个可比较系列值
- **THEN** 后端 MUST 自动绑定该字段为可选 `series` 通道
- **AND** 聚合转换 MUST 按横轴或类别与系列字段联合分组
- **AND** 后端 MUST NOT 把不同系列的同一横轴数据合并为一个数值

#### Scenario: 标题直接指向自定义系列维度
- **WHEN** 图表标题直接包含结构化结果中的自定义分类字段名，或命中地区、部门、渠道、品牌等常用业务别名
- **AND** 该字段具有 2～12 个非空可比较值且未被其他语义通道占用
- **THEN** 后端 MUST 将该字段纳入可选 `series` 候选并按标题相关性选择
- **AND** 趋势图存在月份、日期等时间字段时 MUST 优先使用时间字段作为横轴

#### Scenario: 多系列图保留同系列重复类目
- **WHEN** `bar`、`area`、`step` 或 `line` 包含 `series` 通道且同一系列内多行数据具有相同类别或横轴值
- **THEN** 前端 MUST 按该类别在系列内的出现顺序扩展统一坐标槽
- **AND** 其他系列缺少对应次数的数据时 MUST 使用空值补齐
- **AND** 前端 MUST NOT 只取同系列同类目的第一行

#### Scenario: 单系列图保留重复类别数据
- **WHEN** `bar`、`area`、`step` 或 `line` 没有 `series` 通道且多行数据具有相同类别或横轴值
- **THEN** 前端 MUST 按数据行顺序保留每一个类别和值
- **AND** 前端 MUST NOT 通过类别去重或只取第一行而静默丢失业务数据

#### Scenario: 统计分布图由后端计算
- **WHEN** LLM 选择 `histogram` 或 `boxplot`
- **THEN** 后端 MUST 从来源数值字段计算分箱或五数概括
- **AND** LLM MUST NOT 直接提交分箱计数、四分位数或异常值作为权威业务数据

#### Scenario: 常量直方图生成有效分箱
- **WHEN** 直方图来源数值全部相等
- **THEN** 后端 MUST 围绕该常量生成起点小于终点的递增分箱边界
- **AND** 所有来源行的计数总和 MUST 保持不变

#### Scenario: 关系和层级图校验结构
- **WHEN** LLM 选择 `sunburst`、`treemap` 或 `sankey`
- **THEN** 后端 MUST 分别校验层级标识与父子关系，或来源、目标和值关系
- **AND** 系统 MUST 拒绝循环层级、缺失节点或非数值权重

#### Scenario: 桑基图拒绝有向循环
- **WHEN** 桑基图来源和目标边形成自环或两个及以上节点组成的有向环
- **THEN** 后端 MUST 在生成 `ChartSpec` 前拒绝该规划
- **AND** 系统 MUST NOT 把会导致 ECharts DAG 布局失败的桑基图发送到前端

#### Scenario: 计划和目标类图校验业务字段
- **WHEN** LLM 选择 `waterfall`、`bullet`、`gantt`、`gauge` 或 `liquid-fill`
- **THEN** 后端 MUST 校验累计值、实际值与目标值、开始与结束时间或指标上下界等对应字段
- **AND** 超出上下界、结束时间早于开始时间或单位冲突时 MUST 拒绝图表

#### Scenario: 甘特图展示可选进度
- **WHEN** 合法甘特图包含 `progress` 通道
- **THEN** 前端 MUST 在完整任务范围条上叠加对应的已完成范围
- **AND** 前端 MUST NOT 校验后丢弃进度字段

#### Scenario: 后端自动绑定甘特进度
- **WHEN** 甘特图来源包含名为进度、完成率或 `progress` 的非空数值字段，且全部值位于 0～1
- **THEN** 后端 MUST 自动将该字段绑定为可选 `progress` 通道
- **AND** 后端 MUST NOT 对 0～100 的百分比值执行无声明的静默换算

#### Scenario: 甘特图使用水平时间范围
- **WHEN** 前端渲染合法甘特图
- **THEN** 横轴 MUST 使用时间轴，纵轴 MUST 使用任务类别轴
- **AND** 每个任务 MUST 从开始时间到结束时间绘制水平范围条
- **AND** 渲染 MUST NOT 出现温度单位或竖向温度范围条语义

#### Scenario: 甘特图兼容 MySQL DATETIME
- **WHEN** 甘特图来源时间字段使用 ERP MySQL `yyyy-MM-dd HH:mm:ss` 及可选小数秒格式
- **THEN** 后端 MUST 正确校验开始和结束顺序
- **AND** 最终 `ChartSpec` MUST 将时间统一输出为 ISO-8601 字符串

#### Scenario: 水位图只展示单个业务指标
- **WHEN** 水位图经过显式排序和 limit 后的编译数据仍包含多行
- **THEN** 后端 MUST 拒绝该图表规划
- **AND** 前端 MUST NOT 将多行数据解释为同一个水球的多层波浪

#### Scenario: 仪表盘使用统一默认范围
- **WHEN** LLM 选择 `gauge` 且没有显式提供最小值或最大值
- **THEN** 后端和前端 MUST 对缺失边界分别使用 `0` 和 `100`
- **AND** 业务值超出有效范围时后端 MUST 拒绝图表

#### Scenario: 仪表盘只展示单个业务指标
- **WHEN** 仪表盘经过显式排序和 limit 后的编译数据仍包含多行
- **THEN** 后端 MUST 拒绝该图表规划
- **AND** 前端 MUST NOT 通过只读取第一行而静默丢弃其他指标

#### Scenario: 子弹图数值单位保持一致
- **WHEN** 子弹图 actual、target 或可选 range 通道声明了显式单位
- **THEN** 后端 MUST 校验这些可比较数值通道的单位一致
- **AND** `options.unit` 存在时 MUST 与 actual、target 和 range 的任一显式单位一致
- **AND** 单位冲突时 MUST 拒绝图表并保留文本回答

#### Scenario: 多维图校验数值维度
- **WHEN** LLM 选择 `radar`、`scatter`、`bubble`、`heatmap` 或 `parallel`
- **THEN** 后端 MUST 校验 `radar`、`scatter`、`bubble` 和 `parallel` 所需的数值维度
- **AND** `bubble` MUST 额外具有非负的大小维度
- **AND** `heatmap` MUST 允许类别或数值坐标维度，并要求一个数值维度

#### Scenario: 编译器派生字段不得覆盖业务字段
- **WHEN** 气泡图、水位图或雷达图需要生成的内部字段键与来源业务字段重名
- **THEN** 后端 MUST 为派生字段分配其他唯一字段键
- **AND** `encoding` MUST 引用实际派生字段键
- **AND** 原业务字段和值 MUST 保持不变
- **AND** 前端 MUST 通过 `encoding` 读取派生字段，不得读取固定首选属性名

#### Scenario: 雷达图拒绝负指标
- **WHEN** 雷达图任一指标值小于零
- **THEN** 后端 MUST 拒绝该图表规划
- **AND** 系统 MUST NOT 生成小于指标值的无效雷达上界

#### Scenario: 词云只接收名称和值
- **WHEN** LLM 选择 `word-cloud`
- **THEN** 后端 MUST 要求名称字段和非负数值字段，或从真实结构化记录生成的非负计数
- **AND** 前端 MUST 将名称作为纯文本处理

### Requirement: 问答接口必须同时承载文本与可空图表

系统 SHALL 使非流式问答和项目内统一流式问答同时返回助手文本和可空 `ChartSpec`，并保持旧历史消息的文本回放兼容。

#### Scenario: 非流式响应包含图表
- **WHEN** `GET /api/ask` 对当前问答生成了有效图表
- **THEN** `RespVO<ChatVO.AskResponse>` 的 `data` MUST 包含原有字段和 `chart` 对象
- **AND** `answer` MUST 继续保存和返回 Markdown 文本

#### Scenario: 非流式响应没有图表
- **WHEN** 当前问答没有合法图表
- **THEN** `GET /api/ask` MUST 返回原有文本回答
- **AND** `chart` MUST 为 `null`

#### Scenario: 流式业务查询重试不得泄漏未验证回答
- **WHEN** 流式业务数据问题的首次模型调用没有产生当前轮业务 Tool 结果
- **THEN** 服务端 MUST 在确认当前轮数据来源前暂存首次响应
- **AND** 服务端 MUST NOT 向前端发送首次调用中的未验证业务数字或 Markdown 表格
- **AND** 有界重试成功后 MUST 只发送重试产生的最终业务回答

#### Scenario: 当前轮业务结果确认后恢复实时增量输出
- **WHEN** 流式业务数据问题在模型响应流结束前捕获到当前轮非空结构化 Tool 结果
- **THEN** 服务端 MUST 丢弃业务结果确认前暂存的查询或规划旁白
- **AND** 服务端 MUST 从确认当前轮业务结果时的当前响应开始继续逐分片向下游发送最终回答
- **AND** 服务端 MUST NOT 为等待完整响应或最后一个 usage 分片而聚合整个模型响应流
- **AND** 上游尚未完成时前端 MUST 能收到已经通过最终答案净化的 `delta` 事件

#### Scenario: 缺少最终答案边界时英文执行旁白不得进入回答
- **WHEN** Provider 未输出最终答案边界，并在中文业务答案前输出英文查询或图表规划旁白
- **THEN** 非流式、流式完成阶段和历史读取 MUST 移除可明确识别的英文内部执行前缀
- **AND** 服务端 MUST 保留紧随其后的完整中文业务答案、Markdown 表格和图表
- **AND** 服务端 MUST NOT 对普通英文业务回答执行无依据的删除或翻译

#### Scenario: 缺少最终答案边界时安全正文仍保持流式
- **WHEN** Provider 未输出最终答案边界，但响应前缀已经能够排除内部查询或规划旁白
- **THEN** 服务端 MUST 立即发送已确认的业务正文分片
- **AND** 后续正文 MUST 继续逐分片发送，不得全部延迟到流完成时一次性补发

### Requirement: 图表前端必须安全响应式渲染并可降级

系统 SHALL 在静态前端使用本地化 Apache ECharts 和必要扩展渲染 `ChartSpec`，并通过应用自有适配器生成受控 ECharts option。系统 MUST 保持零构建、无 CDN 的前端约束。

#### Scenario: 助手文本下方展示图表卡片
- **WHEN** 助手消息包含受支持的 `ChartSpec`
- **THEN** 前端 MUST 先渲染 Markdown 文本，再在同一助手消息下方渲染一个图表卡片
- **AND** 图表容器 MUST 在桌面和窄屏布局中自适应宽度

#### Scenario: 图表资源全部本地加载
- **WHEN** 用户打开聊天页面
- **THEN** ECharts 核心、词云扩展和水位图扩展 MUST 从 `static/vendor/` 加载
- **AND** 页面 MUST NOT 请求 CDN 资源

#### Scenario: 前端不执行服务端传入代码
- **WHEN** 前端把 `ChartSpec` 转换为 ECharts option
- **THEN** 前端 MUST 只使用应用内置适配函数和固定 formatter
- **AND** 标题、标签、tooltip 数据和词云文本 MUST 作为纯文本或经过 HTML 转义处理
- **AND** 前端 MUST NOT 执行 `ChartSpec` 中的函数、HTML、URL、CSS 或任意 ECharts option

#### Scenario: 热力图空值不得伪造成零
- **WHEN** 历史热力图数据包含空数值
- **THEN** 前端计算色阶时 MUST 忽略空值且保留数据点的空值语义
- **AND** 前端 MUST NOT 通过 `Number(null)` 把缺失数据转换为业务零值
- **AND** 不存在有效数值时 MUST 降级为仅显示文本

#### Scenario: 渲染失败降级
- **WHEN** 图表库未加载、扩展缺失、数据不合法或 ECharts 渲染抛出异常
- **THEN** 前端 MUST 保留助手文本并移除不可用图表容器
- **AND** 页面其余消息和后续提问 MUST 继续可用

#### Scenario: 图表实例按 DOM 生命周期释放
- **WHEN** 消息列表被清空、切换历史会话、开始新对话或页面尺寸变化
- **THEN** 前端 MUST 对失效图表实例执行释放或重建
- **AND** 仍在页面中的图表 MUST 响应容器尺寸变化

#### Scenario: 长文本内部标识不得碰撞
- **WHEN** 两个系列名称或桑基节点名称前 120 个字符相同但完整值不同
- **THEN** 前端 MUST 使用完整原值区分内部系列、节点和边关系
- **AND** 长度限制 MUST 只作用于展示文本
- **AND** 不同业务数据 MUST NOT 因展示截断而合并

### Requirement: 图表失败不得影响问答主流程

系统 SHALL 将结果捕获、图表规划、编译、序列化、持久化和前端渲染视为可降级能力。任何图表子流程失败 MUST NOT 把已经成功的业务 Tool 查询和文本回答转换为问答失败。

#### Scenario: 服务端图表生成失败
- **WHEN** 图表规划、校验、转换或序列化发生异常
- **THEN** 系统 MUST 记录包含 `traceId` 和阶段的错误摘要
- **AND** 系统 MUST 返回并保存文本回答
- **AND** 系统 MUST NOT 记录或返回半成品 `ChartSpec`

#### Scenario: 后端候选失败后继续自动尝试
- **WHEN** LLM 已选择图表类型，但当前来源生成的字段绑定、语义通道、转换或安全选项未通过完整校验
- **THEN** 当前失败 MUST NOT 占用本轮唯一有效图表名额
- **AND** 后端 MUST 继续尝试本轮其他可兼容来源结果
- **AND** 全部候选失败时规划 Tool MUST 返回不包含业务原始数据、字段名或内部异常的不可重试原因
- **AND** 面向用户的最终回答 MUST NOT 描述自动绑定、校验或降级过程

#### Scenario: 图表持久化失败
- **WHEN** 文本消息可以保存但图表字段无法持久化
- **THEN** 系统 MUST 避免重复保存助手消息或重复扣费
- **AND** 失败 MUST 被记录以便排查

### Requirement: 助手回答不得暴露内部执行旁白

系统 SHALL 只向用户返回与当前问题直接相关的最终业务答案，不得展示业务 Tool 查询、图表规划、校验修正或重试过程。

#### Scenario: 非流式回答包含内部执行文本
- **WHEN** Provider 在最终答案之前输出查询、规划或重试旁白
- **THEN** 非流式接口 MUST 移除内部旁白和最终答案边界标记
- **AND** 持久化的助手消息 MUST 只包含最终业务答案

#### Scenario: 历史消息包含旧版内部旁白
- **WHEN** 历史助手消息在最终 Markdown 区域之前包含可识别的查询或图表规划过程
- **THEN** 历史消息接口 MUST 只返回净化后的业务答案
- **AND** 用户消息 MUST 保持原文

#### Scenario: 合法正文包含内部协议同名词
- **WHEN** 最终业务答案或知识说明包含 `bindings`、`transform`、“重新尝试”或“图表规划”等普通术语
- **THEN** 服务端 MUST 保留该合法正文
- **AND** 服务端 MUST NOT 仅依据单个同名词把完整段落判定为内部执行旁白
