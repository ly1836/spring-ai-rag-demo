## ADDED Requirements

### Requirement: User can stop a streaming answer from the chat page
系统 SHALL 在 `GET /api/ask/stream` 处于活跃流式输出期间提供“停止回答”控制项；当用户触发该控制项时，系统 MUST 终止当前流式请求，且不得新建会话，也不得清空页面上已经渲染出来的部分助手回答。

#### Scenario: Stop control is shown only during an active stream
- **WHEN** 用户发送问题且助手回答进入流式输出状态
- **THEN** 聊天页面显示一个可用的“停止回答”控制项，并且该控制项只作用于当前回答

#### Scenario: User stops an active streaming answer
- **WHEN** 用户在流式回答进行过程中点击“停止回答”
- **THEN** 客户端终止当前流式请求，并保留已经接收到的部分助手内容

### Requirement: User cancellation is distinct from request failure
系统 SHALL 将“用户主动取消流式回答”视为一种独立结束态，而不是通用请求失败。聊天页面 MUST 在取消后恢复到可继续发送问题的状态，且 MUST NOT 为用户取消展示通用的请求失败提示。

#### Scenario: Cancellation does not surface a generic error
- **WHEN** 用户停止一个活跃流，且浏览器抛出与中止相关的异常
- **THEN** 聊天页面屏蔽通用失败 toast 或失败提示，并将该回答标记为“已终止”

#### Scenario: Chat input is restored after cancellation
- **WHEN** 某次流式回答被用户主动取消
- **THEN** 发送按钮、输入焦点以及本地流式状态标记恢复到与正常完成后相同的可发送状态

### Requirement: Cancelled streams are persisted with explicit cancellation semantics
系统 SHALL 持久化被取消流中已累计生成的助手内容；对应的助手消息 MUST 以 `cancelled` 状态写入，以便下游历史记录视图能将其与 `success`、`error` 区分开。取消处理 MUST NOT 触发重复扣费，且任何 token 结算 SHALL 仅基于取消完成前已经实际观测到的 usage 元数据。

#### Scenario: Partial assistant response is saved as cancelled
- **WHEN** 用户在已经收到一个或多个响应分片之后取消流式回答
- **THEN** 对话历史以 `cancelled` 状态保存这条部分助手回答

#### Scenario: Cancellation without observed usage does not create speculative billing
- **WHEN** 用户在服务端尚未观测到可用 token usage 元数据之前取消流式回答
- **THEN** 系统对该取消路径记录 0 token 结算，且不得依据部分文本去估算 token 成本

#### Scenario: Cancellation with observed usage settles only once
- **WHEN** 用户在服务端已经观测到有效 usage 元数据之后取消流式回答
- **THEN** 系统最多只基于已观测到的 usage 结算一次，且不得再执行正常完成路径的重复结算
