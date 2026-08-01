## MODIFIED Requirements

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

## ADDED Requirements

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
