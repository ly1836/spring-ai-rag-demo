# dynamic-llm-tools Specification

## Purpose
定义数据库动态 LLM Tool 的配置管理、运行期加载刷新、租户隔离执行、Tool 命中流水和前端管理能力。
## Requirements
### Requirement: 全局数据库 Tool 配置管理

系统 SHALL 提供全局 LLM Tool 配置能力，通过 ERP MySQL 中的 `a_llm_tool` 表维护数据库定义的 SQL 查询类 Tool。Tool 定义 MUST 不按租户拆分；所有租户共享同一批启用配置，但配置管理接口 MUST 通过受控管理端 API 访问。

#### Scenario: 应用启动加载启用的数据库 Tool
- **WHEN** 应用启动且 `a_llm_tool` 表中存在 `status = active` 的 SQL 查询类 Tool
- **THEN** 系统 MUST 读取这些 Tool 定义并生成可供 Spring AI 使用的 `ToolCallback`
- **AND** 加载失败的单个 Tool MUST 被记录为启动告警，不能影响现有代码 Tool 注册

#### Scenario: 管理端查询全局 Tool 列表
- **WHEN** 用户调用 `GET /api/admin/tools`
- **THEN** 系统 MUST 返回 `a_llm_tool` 中的全局 Tool 配置列表
- **AND** 响应 MUST 使用 `RespVO<List<AdminVO.ToolItem>>`
- **AND** 查询 MUST NOT 按当前 `ent_code` 过滤 Tool 定义

#### Scenario: 新增或修改 Tool 后刷新运行期快照
- **WHEN** 用户通过 `POST /api/admin/tools` 或 `PUT /api/admin/tools` 成功保存 Tool 配置
- **THEN** 系统 MUST 持久化配置并刷新运行期 Tool 快照
- **AND** 后续 auto/data 模式问答 MUST 使用刷新后的 Tool 定义

#### Scenario: 删除或禁用 Tool 后不再暴露给 LLM
- **WHEN** 用户删除 Tool 配置或将 Tool 状态改为 `inactive`
- **THEN** 系统 MUST 刷新运行期 Tool 快照
- **AND** 后续 LLM 请求 MUST NOT 再看到该 Tool 定义

#### Scenario: 非法 Tool 配置被拒绝
- **WHEN** Tool 名称为空、名称重复、JSON Schema 不可解析、SQL 模板为空、SQL 模板不是单层只读查询、主表别名非法或返回行数超出限制
- **THEN** 系统 MUST 拒绝保存并返回 `PARAM_ERROR` 或 `BIZ_ERROR`
- **AND** 系统 MUST NOT 刷新为包含非法配置的 Tool 快照

### Requirement: 数据库 Tool 回调必须租户隔离执行

系统 SHALL 在数据库 Tool 回调执行时读取当前请求的 `TenantContext.requireEntCode()`，并把该租户编码作为 SQL 绑定参数注入查询条件，保证 Tool 定义全局共享但查询结果只来自当前租户。

#### Scenario: SQL 查询自动追加当前租户条件
- **WHEN** 当前请求租户为 `ENT001` 且 LLM 调用数据库 Tool
- **THEN** 系统 MUST 在执行 SQL 前追加当前租户的 `ent_code = ?` 条件
- **AND** SQL 参数列表 MUST 包含 `ENT001`
- **AND** 查询结果 MUST NOT 包含其他租户数据

#### Scenario: JOIN 查询按主表别名追加租户条件
- **WHEN** 数据库 Tool 配置了主表别名且 SQL 为 JOIN 查询
- **THEN** 系统 MUST 使用该别名追加租户条件，例如 `o.ent_code = ?`
- **AND** 系统 MUST 避免因多表存在 `ent_code` 字段而产生列歧义
- **AND** 主表别名 MUST 仅允许字母、数字和下划线，且必须以字母或下划线开头

#### Scenario: 缺失租户上下文时拒绝执行
- **WHEN** 数据库 Tool 回调执行时当前线程或 Reactor Context 中没有可用 `ent_code`
- **THEN** 系统 MUST 拒绝执行 Tool 并记录失败日志
- **AND** 系统 MUST NOT 执行不带租户条件的 SQL

#### Scenario: 只读 SQL 安全边界
- **WHEN** Tool 配置 SQL 包含写入语句、DDL、多语句分号、复杂查询块或危险关键字
- **THEN** 系统 MUST 在保存或刷新阶段拒绝该 Tool 配置
- **AND** 系统 MUST NOT 在运行期执行该 SQL

#### Scenario: Tool 参数使用绑定变量
- **WHEN** LLM 传入 Tool arguments JSON
- **THEN** 系统 MUST 按 `input_schema` 中声明的参数解析并绑定到 SQL 占位符
- **AND** 系统 MUST NOT 将用户输入直接拼接到 SQL 字符串中

### Requirement: 动态 Tool 注册必须兼容现有代码 Tool

系统 SHALL 将现有 `com.example.rag.tool` 代码 Tool 和启用的数据库 Tool 合并为同一份 ToolCallback 快照，并用于 auto/data 模式的 LLM 请求。现有代码 Tool 的名称、描述、入参和查询语义 MUST 保持兼容。

#### Scenario: 现有代码 Tool 仍可被 LLM 调用
- **WHEN** 用户以 auto 或 data 模式提出可由现有 `SalesTool`、`PurchaseTool` 等代码 Tool 回答的问题
- **THEN** LLM MUST 仍能看到并调用这些代码 Tool
- **AND** 代码 Tool 查询 MUST 继续通过 `BaseTool.query()` 或 `BaseTool.queryWithAlias()` 执行租户隔离

#### Scenario: 数据库 Tool 可被 LLM 调用
- **WHEN** 用户以 auto 或 data 模式提出可由启用数据库 Tool 回答的问题
- **THEN** LLM MUST 能看到该数据库 Tool 的名称、描述和入参 schema
- **AND** 系统 MUST 通过数据库 Tool 执行器返回查询结果

#### Scenario: Tool 快照刷新不影响进行中请求
- **WHEN** 一个问答请求正在使用旧 Tool 快照执行，且管理端刷新了 Tool 配置
- **THEN** 进行中的请求 MAY 继续使用旧快照完成
- **AND** 刷新后的新请求 MUST 使用新的 Tool 快照版本

#### Scenario: 多模型 provider 使用同一 Tool 快照
- **WHEN** 用户切换 DeepSeek、OpenAI 兼容或 Google GenAI 模型发起 auto/data 问答
- **THEN** `ErpAssistantService` MUST 通过 `ModelRegistry` 获取对应 `ChatModel`
- **AND** 每个 provider 构建的带 Tool `ChatClient` MUST 使用当前最新 ToolCallback 快照

#### Scenario: 动态 Tool 与代码 Tool 能力重叠时使用通用选择引导
- **WHEN** 启用的数据库 Tool 与现有代码 Tool 都可能回答同一类 ERP 数据问题
- **THEN** 系统 SHOULD 给 LLM 提供优先选择数据库 Tool 的通用引导
- **AND** 系统 MUST NOT 在系统提示词中硬编码窄业务场景、固定客户问题或固定 Tool 名称作为选择约束

### Requirement: Tool 命中记录必须落库

系统 SHALL 为每次业务 LLM Tool 调用追加 `a_tool_call_log` 记录，并在助手消息保存时写入本轮业务 Tool 调用聚合信息。业务 Tool 仅包括 `tool_type = code` 或 `tool_type = database` 的 ERP 查询 Tool；内部图表规划等仅用于系统展示的 Tool MUST NOT 写入业务 Tool 命中流水、`tool_calls` 聚合或 `tool_calls_count`。Tool 日志写入失败 MUST NOT 阻断用户问答主流程。

#### Scenario: 成功调用业务 Tool 记录成功日志
- **WHEN** LLM 成功调用任意代码 Tool 或数据库 Tool
- **THEN** 系统 MUST 插入一条 `a_tool_call_log` 记录
- **AND** 记录 MUST 包含 `trace_id`、`conversation_id`、`ent_code`、`user_id`、`mode`、`model`、`tool_name`、`tool_type`、`arguments_json`、`result_count`、`status = success` 和 `duration_ms`

#### Scenario: 业务 Tool 调用失败记录错误日志
- **WHEN** 代码 Tool 执行抛出异常或数据库 Tool 参数校验失败
- **THEN** 系统 MUST 插入一条 `status = error` 的 `a_tool_call_log` 记录
- **AND** 记录 MUST 包含错误摘要
- **AND** 响应给 LLM 的 Tool 错误处理 MUST 保持 Spring AI 现有异常处理语义

#### Scenario: 助手消息保存业务 Tool 聚合字段
- **WHEN** 一轮非流式或流式问答完成且本轮发生过代码 Tool 或数据库 Tool 调用
- **THEN** 系统保存助手消息时 MUST 写入 `tool_calls` JSON 聚合
- **AND** `tool_calls_count` MUST 等于本轮代码 Tool 与数据库 Tool 调用次数之和

#### Scenario: 内部图表规划不计入业务 Tool 命中
- **WHEN** LLM 调用内部图表规划 Tool
- **THEN** 系统 MUST NOT 向 `a_tool_call_log` 插入该调用
- **AND** 系统 MUST NOT 将该调用追加到助手消息的 `tool_calls`
- **AND** 系统 MUST NOT 增加 `tool_calls_count` 或计费用量中的业务 Tool 次数

#### Scenario: knowledge 模式不产生 Tool 调用记录
- **WHEN** 用户以 knowledge 模式发起问答
- **THEN** 系统 MUST NOT 向 LLM 暴露代码 Tool、数据库 Tool 或内部图表规划 Tool
- **AND** 本轮问答 MUST NOT 产生新的 `a_tool_call_log` 记录

### Requirement: 内部图表规划 Tool 必须与动态 Tool 管理解耦

系统 SHALL 将内部图表规划 Tool 作为 chat 编排能力装配到 auto/data 模式，MUST NOT 将其作为 `a_llm_tool` 配置、动态 Tool 快照版本、管理端 Tool 列表或可编辑数据库 Tool 暴露。

#### Scenario: 动态 Tool 刷新不移除图表规划能力
- **WHEN** 管理员新增、修改、禁用或删除动态数据库 Tool 并刷新运行期快照
- **THEN** 新请求 MUST 使用刷新后的业务 Tool 快照
- **AND** auto/data 模式的内部图表规划能力 MUST 继续可用
- **AND** 内部图表规划 Tool MUST NOT 出现在 `GET /api/admin/tools` 响应中

#### Scenario: 业务 Tool 不得占用内部规划 Tool 名称
- **WHEN** 动态 Tool 或代码 Tool 使用系统保留名称 `plan_chart_visualization`
- **THEN** 动态 Tool 配置校验 MUST 拒绝该名称
- **AND** ChatClient 最终装配 MUST 校验业务 Tool 与内部 Tool 的名称唯一性
- **AND** Provider MUST NOT 收到两个同名函数定义

### Requirement: 面向用户的回答必须隐藏内部 Tool 与数据库标识

系统 SHALL 使用用户当前提问语言描述业务结果，并将 Tool 名称、函数名称、数据库表名、字段名、SQL 和内部调用过程视为非用户信息。用户使用中文提问时，最终回答 MUST 全程使用中文。

#### Scenario: 参数不足时使用业务语言追问
- **WHEN** 业务查询缺少客户名称、订单号或其他必需条件
- **THEN** 助手 MUST 使用用户当前提问语言说明需要补充的业务条件
- **AND** 助手 MUST NOT 列出可用 Tool 名称、函数名称或数据库实现

#### Scenario: 查询成功后只展示业务结论
- **WHEN** 一个或多个业务 Tool 已成功返回数据
- **THEN** 助手 MUST 只展示查询结论、业务字段标签和必要后续提示
- **AND** 助手 MUST NOT 在开场、正文或结束语中暴露内部 Tool 或数据库标识

### Requirement: 工具管理前端页面

系统 SHALL 在静态前端顶部导航中新增「工具管理」入口，放置在「计费管理」按钮右侧，并提供 Tool 配置维护、刷新和调用日志查看能力。

#### Scenario: 顶部导航展示工具管理入口
- **WHEN** 用户打开首页
- **THEN** 顶部导航 MUST 在「计费管理」按钮右侧展示「工具管理」按钮
- **AND** 点击后 MUST 切换到工具管理面板

#### Scenario: 用户维护 Tool 配置
- **WHEN** 用户在工具管理面板新增、编辑、删除或禁用 Tool
- **THEN** 前端 MUST 调用 `/api/admin/tools/**` 管理接口
- **AND** 所有非流式请求 MUST 通过 `apiCall` 或 `apiPost` 自动携带 `X-Ent-Code` 和 `X-User-Id`
- **AND** 操作成功后 MUST 刷新页面列表并提示成功

#### Scenario: Tool 状态展示中文但传输英文
- **WHEN** 用户查看或编辑 Tool 状态
- **THEN** 前端 MUST 向用户展示中文状态文案，例如 `启用`、`停用`
- **AND** 前后端请求和响应中的状态值 MUST 保持英文枚举 `active`、`inactive`

#### Scenario: 入参 Schema 提供说明和示例
- **WHEN** 用户编辑 Tool 入参 Schema
- **THEN** 前端 MUST 标明该字段为 JSON Schema
- **AND** 前端 MUST 提示字段名需要与 SQL 模板参数名一致，`description` 用于帮助模型理解参数含义
- **AND** 清空新建表单后 MUST 提供可编辑的示例 Schema 和示例数据说明

#### Scenario: 用户查看当前租户 Tool 调用日志
- **WHEN** 用户在工具管理面板打开调用日志
- **THEN** 前端 MUST 查询 `/api/admin/tools/call-logs`
- **AND** 后端返回的日志 MUST 按当前租户隔离
- **AND** 后端 SHOULD 支持按创建时间倒序分页查询当前租户日志
- **AND** 动态渲染字段进入 `innerHTML` 前 MUST 使用 `escapeHtml()`

#### Scenario: Tool 命中流水来源展示中文但传输英文
- **WHEN** 用户查看 Tool 命中流水的来源字段
- **THEN** 前端 MUST 向用户展示中文来源文案，例如 `代码工具`、`动态工具`
- **AND** 前后端请求和响应中的来源值 MUST 保持英文枚举 `code`、`database`

#### Scenario: 工具管理页不受计费子 Tab 切换影响
- **WHEN** 用户切换计费管理下的子 Tab
- **THEN** 前端 MUST 只隐藏或展示计费管理区域内的内容面板
- **AND** 系统 MUST NOT 因计费子 Tab 切换隐藏工具管理页内容

### Requirement: 数据库初始化包含动态 Tool 表

系统 SHALL 在应用启动初始化 ERP MySQL 时创建动态 Tool 配置表和 Tool 调用日志表，并可幂等插入演示数据库 Tool 配置。

#### Scenario: 空库启动创建 Tool 表
- **WHEN** ERP MySQL 中不存在动态 Tool 相关表
- **THEN** 应用启动初始化 MUST 创建 `a_llm_tool` 和 `a_tool_call_log`
- **AND** 初始化 MUST 使用 ERP MySQL 数据源

#### Scenario: 重复启动不重复插入演示 Tool
- **WHEN** 应用已经插入过同名演示 Tool 并再次启动
- **THEN** 初始化 MUST 以 `tool_name` 判断记录是否存在
- **AND** 已存在 Tool 配置 MUST NOT 被覆盖

#### Scenario: 初始化包含库存批次库位查询演示 Tool
- **WHEN** 应用初始化动态 Tool 演示数据
- **THEN** 初始化脚本 MUST 包含 `query_inventory_lot_location`
- **AND** 该 Tool MUST 使用 `lotNo` 作为入参
- **AND** 该 Tool MUST 查询库存表并通过主表别名执行租户隔离

#### Scenario: 全局 Tool 配置表跳过租户插件
- **WHEN** MyBatis-Plus 查询 `a_llm_tool`
- **THEN** 系统 MUST 将 `a_llm_tool` 配置为忽略租户隔离表
- **AND** 查询 Tool 定义时 MUST NOT 自动追加 `ent_code` 条件
