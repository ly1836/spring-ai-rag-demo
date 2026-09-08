# chat-history-resume 规格

## Purpose
定义 ERP 智能助手中“从历史记录重新进入对话并继续会话”的能力，包括历史续聊入口、AI 对话面板状态衔接、模式继承、软删除/不存在会话的拒绝语义，以及不刷新页面退出当前会话并开始新对话的交互规则。
## Requirements
### Requirement: 历史会话支持继续对话入口

系统 SHALL 在「历史记录」会话列表中为每条非软删除的会话提供「继续对话」控制项；当用户触发该控制项时，系统 MUST 将当前激活的 Tab 切换到「AI 对话」面板，并把该面板的会话上下文衔接到所选历史会话。

#### Scenario: 历史列表展示继续对话入口

- **WHEN** 用户进入「历史记录」Tab 并加载到一条 `status` 不为 `deleted` 的会话
- **THEN** 该会话所在行渲染一个可用的「继续对话」控制项

#### Scenario: 软删除会话不展示可用入口

- **WHEN** 历史列表中存在一条 `status = deleted` 的会话
- **THEN** 该会话不会暴露可点击的「继续对话」控制项，或将其置为不可用状态

#### Scenario: 触发继续对话会切换至 AI 对话面板

- **WHEN** 用户在历史列表中点击某条会话的「继续对话」控制项
- **THEN** 系统将顶部 Tab 切换为「AI 对话」，并隐藏欢迎页与预置问题区域

### Requirement: 智能对话面板续聊状态衔接

系统 SHALL 在切换到「AI 对话」面板的同时，把所选历史会话的消息渲染到聊天容器中，并把面板内部的会话标识切换为该历史会话的 `conversationId`，使后续提问追加在同一会话上。

#### Scenario: 续聊后会话标识被替换为历史会话 ID

- **WHEN** 用户从历史列表触发「继续对话」
- **THEN** 「AI 对话」面板的当前会话标识更新为历史会话的 `conversationId`，并体现在底部会话标签上

#### Scenario: 历史消息按既有样式渲染到聊天容器

- **WHEN** 续聊跳转完成
- **THEN** 历史会话的消息按时间正序渲染在聊天容器内，user 消息按文本气泡显示，assistant 消息按 Markdown 渲染并执行代码块语法高亮

#### Scenario: 已取消与失败历史消息保留状态标识

- **WHEN** 续聊渲染的历史消息中包含 `status = cancelled` 或 `status = error` 的助手消息
- **THEN** 这些消息以与「历史记录」Tab 一致的状态角标展示，且不影响后续提问的发送状态

#### Scenario: 续聊后输入框可立即发送新提问

- **WHEN** 续聊跳转完成且用户输入新提问并发送
- **THEN** 该提问通过现有 `GET /api/ask/stream` 以同一 `conversationId` 提交，系统正确把它追加到原会话之后

#### Scenario: 续聊后可不刷新页面退出当前会话

- **WHEN** 用户已经从历史记录重新进入某个会话，并在「AI 对话」面板点击「新建对话」或等价的退出当前会话控制项
- **THEN** 系统在不刷新页面的情况下清空当前聊天容器、移除当前 `conversationId`、恢复欢迎态，并允许用户开始一个全新的会话

### Requirement: 续聊默认继承会话最后一条消息的模式

系统 SHALL 在续聊跳转时，把「AI 对话」面板的当前问答模式（`auto` / `data` / `knowledge`）默认设置为该历史会话最后一条消息的 `mode`；若历史会话不存在任何带 `mode` 的消息，则保持面板进入续聊前的当前模式。模式仍 MUST 允许用户在面板上自由切换。

#### Scenario: 最后一条消息的模式被作为续聊默认模式

- **WHEN** 用户对一条最后消息 `mode = data` 的历史会话触发「继续对话」
- **THEN** 「AI 对话」面板的当前模式被同步为 `data`，并在模式切换 UI 上反映该选择

#### Scenario: 用户可在续聊后切换模式

- **WHEN** 用户已经进入续聊状态，并在面板上把模式切换为另一种
- **THEN** 后续提问按新选模式提交，且不影响 `currentConversationId` 与历史消息渲染

### Requirement: 流式输出期间禁止触发继续对话

系统 SHALL 在「AI 对话」面板存在正在进行的流式输出时，拒绝任何「继续对话」跳转动作，避免覆盖正在进行的会话状态或丢失用户当前提问。

#### Scenario: 流式期间点击继续对话被拒绝

- **WHEN** 「AI 对话」面板正处于流式输出中，且用户点击历史列表中的「继续对话」
- **THEN** 系统不切换会话上下文，并向用户提示需要先停止当前回答

#### Scenario: 停止当前回答后可正常续聊

- **WHEN** 用户先在「AI 对话」面板触发停止回答能力，再点击历史列表中的「继续对话」
- **THEN** 续聊跳转按正常流程完成，会话标识、历史消息渲染与模式同步均正确生效

### Requirement: 软删除会话拒绝继续写入

系统 SHALL 在 `prepareConversation` 前置路径上对非空会话 ID 做权威校验：当目标 `conversationId` 在当前租户下不存在时，MUST 返回「会话不存在，不可继续」；当目标会话存在且 `status = deleted` 时，MUST 返回「会话已删除，不可继续」。这两类场景均 MUST 拒绝写入任何新消息，并以 `BIZ_ERROR` 语义向调用方返回明确错误，绝不允许在非法会话上沉淀「幽灵消息」。若调用方不传 `conversationId` 或传空字符串，系统 SHALL 创建新会话。

#### Scenario: 续写软删除会话被服务端拒绝

- **WHEN** 调用方以一个 `status = deleted` 的 `conversationId` 触发任意问答接口（包括 `GET /api/ask/stream`）
- **THEN** 服务端在写入用户消息之前抛出业务异常，由全局异常处理器返回 `BIZ_ERROR`，且 `a_chat_message` 不会新增任何记录

#### Scenario: 续写正常会话不受影响

- **WHEN** 调用方以一个不存在或 `status` 不为 `deleted` 的 `conversationId` 触发问答接口
- **THEN** 服务端继续按原 `INSERT IGNORE` 路径创建/复用会话并保存用户消息，行为与本变更前完全一致

#### Scenario: 空会话 ID 创建新会话

- **WHEN** 调用方不传 `conversationId` 或传入空字符串触发问答接口
- **THEN** 服务端生成新的 `conversationId` 并创建新会话，流式接口 MUST 通过响应头 `X-Conversation-Id` 返回该会话 ID

#### Scenario: 乱传不存在的会话 ID 被拒绝

- **WHEN** 调用方传入一个当前租户下不存在的非空 `conversationId` 触发问答接口
- **THEN** 服务端在写入用户消息之前抛出业务异常，由全局异常处理器返回 `BIZ_ERROR` 和「会话不存在，不可继续」

#### Scenario: 跨租户访问不会绕过校验

- **WHEN** 调用方以另一租户的 `conversationId` 触发问答接口
- **THEN** 服务端按当前 `ent_code` 查询不到该会话记录，并按「会话不存在，不可继续」拒绝；不会因软删除校验泄漏跨租户数据

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

### Requirement: 成功助手消息必须保存知识库选择和引用快照

系统 SHALL 将知识问答实际使用的 `knowledgeBaseId`、资格过滤后的 `ragDocCount` 和经验证 `citations` JSON 与成功助手消息原子保存。用户消息以及 `cancelled`、`error` 助手消息的引用 MUST 为空；旧消息缺少新字段时 MUST 按空值兼容。

#### Scenario: 成功知识回答保存证据快照
- **WHEN** 一轮知识问答成功完成并产生合法引用
- **THEN** 系统 MUST 在同一条助手消息保存实际知识库 ID、真实召回分片数和完整引用快照
- **AND** 这些字段 MUST 与消息共享相同 `messageId`、`conversationId`、`ent_code` 和 `user_id` 访问边界

#### Scenario: 无合法引用的成功回答
- **WHEN** 知识问答成功但答案没有合法引用
- **THEN** 系统 MUST 保存实际知识库 ID 和真实 `ragDocCount`
- **AND** 引用 MUST 保存为可解析的空数组或空值并在接口中返回 `[]`

#### Scenario: 失败或取消消息不保存引用
- **WHEN** 助手消息以 `cancelled` 或 `error` 状态结束
- **THEN** `rag_citations` MUST 为空
- **AND** 系统 MUST NOT 把尚未完成校验的候选引用写入历史

#### Scenario: 旧消息保持兼容
- **WHEN** 历史消息的 `knowledge_base_id` 或 `rag_citations` 为 `NULL`
- **THEN** 历史接口 MUST 返回可空知识库 ID 和 `citations = []`
- **AND** 前端 MUST 继续正常渲染原消息文本、状态和图表

### Requirement: 历史详情和续聊必须回放不可变引用快照

系统 SHALL 在 `GET /api/conversations/{conversationId}/messages` 中返回已保存的知识库 ID、召回数和引用。历史详情及续聊页面 MUST 直接回放该快照，MUST NOT 为回放访问 PgVector、重新调用 LLM 或根据文档当前内容重建引用。

#### Scenario: 历史详情回放引用
- **WHEN** 用户打开包含引用的历史会话详情
- **THEN** 接口 MUST 在对应助手消息中返回保存时的引用顺序、来源、版本和摘要
- **AND** 页面 MUST 在该消息下渲染相同引用卡片

#### Scenario: 文档替换后历史引用不改变
- **WHEN** 某引用文档在回答后上传了新版本
- **THEN** 旧助手消息 MUST 继续显示回答时保存的文档版本和摘要
- **AND** 系统 MUST NOT 将引用改写为新版本

#### Scenario: 文档删除后历史引用仍可回放
- **WHEN** 某引用文档在回答后被删除
- **THEN** 历史消息 MUST 继续返回原引用快照
- **AND** 页面 MUST NOT 因当前文档不存在而隐藏整条历史回答

#### Scenario: 引用 JSON 损坏或超限
- **WHEN** 历史记录中存在不可解析或超过读取上限的引用 JSON
- **THEN** 系统 MUST 记录不含敏感正文的告警并把该条引用降级为 `[]`
- **AND** 系统 MUST 继续返回同一会话的其他消息

#### Scenario: 引用历史查询保持用户和租户隔离
- **WHEN** 调用方查询、续聊或归档会话
- **THEN** 系统 MUST 继续使用 `ent_code + user_id + conversationId` 联合校验所有权
- **AND** 引用中的文档 ID 或知识库 ID MUST NOT 绕过会话隔离泄漏其他用户数据

### Requirement: 从历史续聊时必须恢复可用的知识库选择

系统 SHALL 在进入历史会话续聊时读取最后一条具有非空 `knowledgeBaseId` 的成功助手消息。若该知识库仍属于当前租户且为 `active`，前端 MUST 将其设为当前知识库；若已停用或删除，历史引用仍 SHALL 回放，但新知识问答 MUST 等待用户选择可用知识库而不能静默切换。

#### Scenario: 恢复仍可用的历史知识库
- **WHEN** 历史会话最后使用的知识库仍为当前租户的 `active` 知识库
- **THEN** 续聊页面 MUST 选中该知识库
- **AND** 后续知识问答 MUST 携带该 `knowledgeBaseId`

#### Scenario: 历史知识库已不可用
- **WHEN** 历史会话最后使用的知识库已停用或删除
- **THEN** 页面 MUST 保留所有历史引用并提示重新选择知识库
- **AND** 系统 MUST NOT 静默改用默认知识库发送下一次 knowledge 问答

#### Scenario: 旧会话没有知识库记录
- **WHEN** 历史会话所有消息的 `knowledgeBaseId` 都为空
- **THEN** 页面 MUST 保持进入续聊前的有效选择或解析当前租户默认知识库
- **AND** 旧历史消息 MUST 继续按无引用方式展示

