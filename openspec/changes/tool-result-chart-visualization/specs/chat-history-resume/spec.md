## ADDED Requirements

### Requirement: 助手图表必须随消息持久化并支持历史回放

系统 SHALL 将成功问答生成的最终 `ChartSpec` 与对应助手消息原子保存，并在历史消息查询中以可空 `chart` 对象返回。历史记录和续聊页面 MUST 使用已保存对象回放图表，MUST NOT 为回放重新调用 LLM 或业务 Tool。

#### Scenario: 成功助手消息保存图表
- **WHEN** 一轮问答成功生成合法 `ChartSpec`
- **THEN** 系统 MUST 将图表 JSON 保存到同一条助手消息
- **AND** 图表与消息 MUST 共享相同的 `messageId`、`conversationId`、`ent_code` 和 `user_id` 访问边界

#### Scenario: 无图表消息保持兼容
- **WHEN** 用户消息、knowledge 模式助手消息、不可图表化回答或历史旧消息没有图表
- **THEN** 图表数据库字段 MAY 为 `NULL`
- **AND** 历史消息接口 MUST 返回 `chart = null`
- **AND** 前端 MUST 按原有方式渲染消息文本

#### Scenario: 历史详情回放图表
- **WHEN** 用户打开包含图表的历史会话详情
- **THEN** `GET /api/conversations/{conversationId}/messages` MUST 在对应助手消息中返回已持久化 `chart`
- **AND** 历史详情 MUST 在消息文本下方渲染该图表
- **AND** 系统 MUST NOT 重新生成图表数据

#### Scenario: 续聊页面恢复图表
- **WHEN** 用户从历史记录进入包含图表的会话继续对话
- **THEN** AI 对话面板 MUST 按消息时间顺序恢复文本、状态和图表
- **AND** 每条助手消息最多恢复一个图表
- **AND** 恢复完成后用户 MUST 能继续发送新问题

#### Scenario: 取消或失败消息不持久化图表
- **WHEN** 助手消息以 `cancelled` 或 `error` 状态保存
- **THEN** 该消息的图表字段 MUST 为 `NULL`
- **AND** 历史页面 MUST 继续显示既有取消或失败状态角标

#### Scenario: 图表历史查询保持租户隔离
- **WHEN** 调用方查询当前租户下的会话消息
- **THEN** 图表 MUST 仅随已通过现有 `ent_code` 隔离的消息返回
- **AND** 调用方 MUST NOT 通过 `messageId`、`conversationId` 或图表来源信息读取其他租户的图表

#### Scenario: 同租户用户之间保持会话所有权隔离
- **WHEN** 当前用户尝试读取、续聊或归档同一租户内其他用户的 `conversationId`
- **THEN** 系统 MUST 使用 `ent_code`、`user_id` 和 `conversationId` 联合校验会话所有权
- **AND** 系统 MUST 按会话不存在拒绝请求
- **AND** 系统 MUST NOT 读取消息、调用 LLM 或更新会话状态
