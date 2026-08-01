## ADDED Requirements

### Requirement: 流式问答必须提供统一的结构化事件协议

系统 SHALL 通过 `GET /api/ask/stream` 直接返回结构化 SSE 事件。项目内前端与后端 MUST 使用同一协议，服务端 MUST NOT 维护纯文本和结构化事件的并行版本。协议统一 MUST NOT 改变会话创建、模型路由、租户隔离、Tool Calling、计费或取消语义。

#### Scenario: 流式请求返回结构化事件
- **WHEN** 调用方请求流式问答
- **THEN** 服务端 MUST 使用 `delta`、`chart`、`done`、`error` 事件类型
- **AND** 每个事件的 `data` MUST 是可独立解析的 JSON 对象
- **AND** 调用方 MUST NOT 通过协议版本参数切换为纯文本事件

#### Scenario: 成功事件顺序稳定
- **WHEN** 流式问答成功完成
- **THEN** 服务端 MUST 先发送零个或多个 `delta` 事件
- **AND** 若存在有效图表，MUST 在全部 `delta` 之后发送一个 `chart` 事件
- **AND** 服务端 MUST 最后发送且只发送一个 `done` 事件
- **AND** 同一轮 MUST NOT 发送多个 `chart` 事件

#### Scenario: 连接关闭前必须收到完成事件
- **WHEN** 前端 ReadableStream 已关闭但本轮没有收到 `done` 事件
- **THEN** 前端 MUST 将本轮视为连接提前中断
- **AND** 已收到但尚未确认的 `chart` MUST NOT 渲染
- **AND** 已接收文本 MAY 保留，但页面 MUST 提示本轮未正常完成

#### Scenario: 文本事件只承载最终回答
- **WHEN** LLM 在 Tool Calling 阶段产生查询、规划或重试旁白，并在全部 Tool 完成后声明最终答案边界
- **THEN** 服务端 MUST NOT 为边界之前的文本发送 `delta` 事件
- **AND** `delta` 事件 MUST 通过 JSON 字段 `text` 承载边界之后的最终答案分片
- **AND** 最终答案中的换行、Markdown 和 Unicode 字符 MUST 经过 JSON 编码后可无损还原
- **AND** 最终答案边界标记 MUST NOT 返回给前端或写入助手消息

#### Scenario: Provider 未声明最终答案边界
- **WHEN** Provider 未输出最终答案边界标记，但流式前缀已能安全判定为业务正文
- **THEN** 服务端 MUST 立即发送已确认正文并继续逐分片发送后续 `delta`
- **AND** 服务端 MUST NOT 为等待响应完成而无条件缓存全部正文
- **AND** 只有始终无法安全判定的内容 MAY 在完成阶段净化后通过兼容 `delta` 发送

#### Scenario: Provider 无边界时英文执行旁白不得作为兼容回答发送
- **WHEN** Provider 未输出最终答案边界，并在中文最终答案前输出可明确识别的英文查询或规划旁白
- **THEN** 服务端 MUST 暂存该英文前缀直至能够安全定位后续中文业务正文
- **AND** 服务端 MUST 移除英文内部执行前缀并立即开始逐分片发送后续业务回答

#### Scenario: SSE 的 CRLF 跨网络分片仍保持事件边界
- **WHEN** `\r\n` 的两个字节被 ReadableStream 拆分到相邻网络分片
- **THEN** 前端 MUST 将其规范化为单个换行
- **AND** `event:` 与 `data:` 行 MUST 继续属于同一个类型化事件
- **AND** 前端 MUST NOT 因伪造的空行丢失 `delta`、`chart` 或 `done` 事件

#### Scenario: 流内异常发送错误事件
- **WHEN** SSE 响应头已经发送后流式处理发生异常
- **THEN** 服务端 MUST 尽力发送一个不含内部堆栈的 `error` 事件
- **AND** 本轮 MUST NOT 再发送 `chart` 或成功 `done` 事件

#### Scenario: 前端错误展示复用当前助手消息
- **WHEN** 前端已经创建当前轮助手消息后收到 `error` 事件或连接异常
- **THEN** 前端 MUST 在当前助手气泡中保留已接收的安全文本并追加错误提示
- **AND** 前端 MUST NOT 再创建重复的助手消息或遗留空白气泡

### Requirement: 用户取消时不得发布未完成图表

系统 SHALL 只在助手文本成功完成且图表已经完整编译后发布 `chart` 事件。用户取消或流式异常 MUST NOT 向前端发布未完成图表，也 MUST NOT 在取消消息上持久化图表。

#### Scenario: 图表发送前用户取消
- **WHEN** 用户在最后一个文本分片完成前取消流式回答
- **THEN** 客户端 MUST 保留已接收的最终答案文本分片并标记回答已终止
- **AND** 服务端 MUST NOT 把尚未越过最终答案边界的内部旁白作为部分回答发送或保存
- **AND** 服务端 MUST NOT 发送 `chart` 事件
- **AND** 持久化的 cancelled 助手消息 MUST 不包含图表

#### Scenario: 取消或异常发生在最终答案边界中间
- **WHEN** 流式输出只收到 `<!--FINAL_ANSWER-->` 的合法前缀后被取消或发生异常
- **THEN** 服务端 MUST 将该未完成标记作为内部协议片段丢弃
- **AND** 已发送的 `delta`、取消消息和失败消息 MUST NOT 包含该片段

#### Scenario: 取消路径保持单次结算
- **WHEN** 包含业务 Tool 调用的流式回答被用户取消
- **THEN** 系统 MUST 沿用既有 usage 观测和最多一次结算规则
- **AND** 图表规划或图表缺失 MUST NOT 触发额外 LLM 调用、额外计费或重复扣费
