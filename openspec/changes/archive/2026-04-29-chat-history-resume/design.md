## Context

「历史记录」Tab 当前是只读视图：列表行点击后调 `GET /api/conversations/{id}/messages` 拉消息，渲染到 `historyMessages` 容器，没有任何续聊入口。「AI 对话」Tab 则按 `currentConversationId` 维护当前会话，首次发问时由 `crypto.randomUUID()` 生成；通过 `newConversation()` 重置。

后端续聊能力实际上已经具备：

- `ErpAssistantService.ask*Stream(question, conversationId, modelId)` 接受任意 `conversationId`；
- `prepareConversation()` 调用 `ChatHistoryService.ensureConversation()`，使用 `INSERT IGNORE` 幂等，已有会话不会冲突；
- `MessageChatMemoryAdvisor` + `JdbcChatMemoryRepository.findByConversationId()` 自动从 `a_chat_message` 表加载最近 20 条消息（`MessageWindowChatMemory.maxMessages = 20`，且过滤 `content IS NOT NULL AND status = 'success'`），把历史塞进 prompt；
- `MessageChatMemoryAdvisor` 在 Spring AI 流程内会把当前提问作为单独的 user 消息发送，`JdbcChatMemoryRepository` 已经做了「剔除尾部 user」的兼容处理。

也就是说，只要前端把 `currentConversationId` 替换成历史会话 ID 再发起 `GET /api/ask/stream`，后端就能正确续聊。

但当前实现有一个潜在风险：`ChatHistoryService.ensureConversation()` 是 `INSERT IGNORE`，对软删除会话（`a_chat_conversation.status = 'deleted'`）不会改状态，但 `saveUserMessage()` / `saveAssistantMessage()` 仍会成功写入 `a_chat_message`。这些消息因 `WHERE status != 'deleted'` 在列表中不可见，形成「幽灵消息」。当前界面没有续写已删除会话的入口所以无法触发，新增「继续对话」入口后这条路径就被打开，需要补防御层。

本变更跨越前端历史列表 / 聊天面板与后端会话写入防御，复用现有接口与持久化能力，新增工作量集中在前端状态衔接与一处后端校验。

## Goals / Non-Goals

**Goals:**

- 历史会话支持「继续对话」：在原 `conversationId` 上追加新消息，自动携带历史上下文。
- 续聊入口与现有 AI 对话面板能力（流式输出、停止回答、模式切换、模型选择、计费状态显示）完全一致，避免在历史 Tab 重复实现这些机制。
- 续聊 `currentMode` 默认继承会话最后一条消息的 `mode`，用户仍可在面板上切换。
- 软删除会话拒绝任何后续写入，避免「幽灵消息」；前端入口同步禁用，但以后端校验为权威。
- 前端正在流式输出时禁用「继续对话」入口，避免覆盖正在进行的会话状态。

**Non-Goals:**

- 不新增后端接口；`/api/ask/stream`、`/api/conversations/{id}/messages`、`/api/conversations` 协议保持不变。
- 不调整 `MessageWindowChatMemory.maxMessages` 上下文窗口（继续 20 条），不在 UI 上显式提示窗口大小。
- 不实现「重试某条 user 消息」「继续生成上一次 cancelled 半截回答」等扩展能力。
- 不修改 `JdbcChatMemoryRepository` 的过滤条件（`cancelled` / `error` 仍不参与上下文）。
- 不修改 `a_chat_conversation` / `a_chat_message` 表结构；不引入新的会话状态。
- 不改变租户隔离与计费扣费的现有语义。

## Decisions

### 1. 续聊入口落在「AI 对话」面板，历史 Tab 仅做跳转

历史列表行新增「▶ 继续」按钮，点击后切换到 `tabAiChat`，把选中 `conversationId` 注入 `currentConversationId`，并把历史消息渲染到 `chatMessages` 容器。「AI 对话」面板的输入框、模式选择、模型选择、流式输出与停止回答完全复用，无新增组件。

选择原因：
- 项目零构建原生 JS，没有组件抽象层；如果在历史 Tab 重新实现一套发送/流式/取消，会重复 `sendQuestion()` / `setStreamingState()` / `AbortController` / 计费提示等逻辑，长期维护成本高。
- 视觉上「历史 = 回看」「AI 对话 = 生产」语义清晰，用户对入口预期一致。
- 现有 `chat-streaming-cancellation` 能力（停止回答）天然适用，无需在历史 Tab 单独适配。

备选方案：
- 在历史 Tab 直接挂输入框：违反两个面板的单一权责，需要复制大量状态机代码。
- 使用 modal 弹层续聊：与现有 Tab 导航不一致，且 modal 内难以复用代码块语法高亮与流式状态。

### 2. 前端新增 `continueConversation(conversationId)` 函数承载状态衔接

新增一个前端函数，统一负责续聊跳转的所有步骤：

1. 校验：当前 `isStreaming` 为 true 时拒绝并 toast 提示「请先停止当前回答」。
2. 拉历史：复用 `GET /api/conversations/{id}/messages`（已存在，自动按租户隔离）。
3. 切 Tab：调 `switchTab('aiChat')`，确保面板可见且 hints 容器隐藏。
4. 重置面板：清空 `chatMessages`，移除欢迎页节点。
5. 写状态：`currentConversationId = conversationId`；`currentMode = 历史最后一条消息的 mode || currentMode`；调用 `updateConversationTag()` 更新底部标签；同步模式切换按钮 UI。
6. 渲染气泡：按 `m.role` 调用现有 `addMessage()` / `renderAssistantBubble()`；对 `assistant` 消息走 Markdown 与 `highlightCodeBlocks()`；对 `cancelled` / `error` 状态在气泡上挂同样的状态角标（与历史 Tab 表现一致）。
7. 收尾：滚动到底部，输入框 `focus()`。

选择原因：
- 把所有衔接逻辑收敛到单一函数，避免分散修改 `switchTab()` / `loadMessages()` / `sendQuestion()` 的状态机。
- 渲染走 `addMessage()` / `renderAssistantBubble()` 而不是直接拼 innerHTML，确保后续发送新消息时气泡样式与续聊渲染的旧气泡完全一致。

备选方案：
- 沿用 `loadMessages()` 拼字符串的方式：与「AI 对话」面板的 DOM 结构不同（如 `data-rawText`、`markdown-body` 容器），后续追加新消息会出现样式分裂。

### 3. 续聊 `currentMode` 取「最后一条消息的 mode」

会话表 `a_chat_conversation.mode` 仅是创建时的快照，实际每条 `a_chat_message.mode` 独立记录。续聊时取消息列表最后一条（含 user / assistant，不限状态）的 `mode` 写入 `currentMode`，并把模式切换按钮 UI 同步到该模式；用户随后在面板上切换不受影响。

选择原因：
- 与用户「上一次正在做的事」语义一致；如果上次已经从 `auto` 切到了 `data`，续聊默认也保持 `data`。
- 落到消息粒度而非会话粒度，避免会话跨多种模式后被首条 mode 牵制。

备选方案：
- 取会话表 `mode`（首条快照）：长会话漂移后不准确。
- 沿用 AI 对话面板当前模式：用户从历史跳过来时往往没意识到面板上的模式。
- 强制弹窗确认：交互成本高，频繁打断。

### 4. 软删除会话拒绝写入：在 `ErpAssistantService.prepareConversation()` 入口前置独立校验

新增 `ChatHistoryService.requireConversationActive(conversationId)` 方法：执行一次 `SELECT status FROM a_chat_conversation WHERE conversation_id = ? AND ent_code = ?` 查询，若查到记录且 `status = 'deleted'` 则抛 `IllegalStateException("会话已删除，不可继续")`，由 `GlobalExceptionHandler` 转 `BIZ_ERROR`。`prepareConversation()` 在 `try-catch` **之外**优先调用该方法，确保业务级拒绝信号不会被下方对持久化异常的兜底 `catch` 吞掉，从而真正阻断后续的消息写入、配额校验与 LLM 调用。其余情况（不存在 / `status = 'active'`）继续执行原 `INSERT IGNORE` 路径。

选择原因：
- 入口在前端禁用是兜底，但 `conversationId` 是 query 参数，绕过前端就能续写删除会话。
- 校验只增加一次主键查询，对正常路径性能可忽略。
- 与现有 `prepareConversation()` 中既有的「业务异常显式 rethrow」模式一致，便于扩展更多业务级前置检查。
- 不污染 `ensureConversation()` 的语义（保持其纯粹的「幂等创建」职责），两者关注点分离。

备选方案：
- 把校验内嵌进 `ensureConversation()`：但该方法被 `@Transactional initConversationAndSaveUserMessage` 包裹，且 `prepareConversation()` 的兜底 `catch (Exception)` 会把任何抛出的业务异常吞掉，导致防御层失效；移出后保留 `ensureConversation()` 的纯粹性。
- 把 `INSERT IGNORE` 改为「先 SELECT 再 INSERT」并在状态非 active 时拒绝：与现并发幂等模型偏差较大，并未带来额外好处。
- 物理删除会话 + 级联删除消息：与已确立的「软删除可恢复」策略冲突，不在本变更范围。
- 续写时把会话 `UPDATE status='active'` 复活：违反软删除语义，会让删除按钮失去意义。

### 5. ChatMemory 上下文窗口保持 20 条，UI 不强提示

`MessageWindowChatMemory.maxMessages = 20` 与 `JdbcChatMemoryRepository` 的 `LIMIT 20` 不变。长会话续聊只携带最近 20 条上下文，与新建会话场景一致。UI 不显式提示窗口大小，避免引入新概念。

选择原因：
- 与现有「会话越长上下文越漂移」的行为一致，没有破坏性变更。
- 实际使用中长会话占比低，先观察用户反馈再决定是否暴露。

备选方案：
- 在续聊时在底部 tag 上挂「上下文窗口 20 条」提示：增加 UI 复杂度，对大多数场景是噪音。
- 把 `maxMessages` 提升到 50+：影响所有问答路径，超出本变更范围。

### 6. cancelled / error 历史消息照常回放，不影响续聊语义

历史回放时 `cancelled` 与 `error` 消息正常渲染（带状态角标），但因为 `JdbcChatMemoryRepository` 的 SQL 已经过滤 `status = 'success'`，它们不会进入 LLM 上下文。续聊时 LLM 看不到这些消息，是 `chat-streaming-cancellation` spec 已经定下的取舍，本变更不修改。

选择原因：
- 与已归档 `chat-streaming-cancellation` spec 一致，避免再开取舍。
- 用户在历史里看到的状态角标已经足够说明「这条没成功」，不需要额外说明。

备选方案：
- 强制不在历史 UI 显示 cancelled / error：会丢失「上次发生了什么」的可观察性。
- 把 cancelled / error 灌入 LLM 上下文：与现有 spec 冲突，且 cancelled 内容本就被截断，可能误导推理。

## Risks / Trade-offs

- [`ensureConversation` 新增 SELECT 拉低吞吐] → 单次主键查询成本极低（毫秒级），与 LLM 调用耗时（秒级）相比可忽略；如真出现热点，可在后续改造为 `INSERT ... ON DUPLICATE KEY UPDATE` 配合状态判断。
- [续聊渲染历史消息时若历史很长，前端首屏渲染压力] → 会话 `message_count` 在 UI 上已可见，用户对长会话有预期；现有历史 Tab 渲染同样数量未见性能问题，本变更复用同一渲染路径。
- [模式继承「最后一条 mode」可能与会话 `mode` 字段不一致] → 已有数据每条消息独立带 `mode`，统计或导出场景需要时按消息粒度聚合即可，本变更不修改任何写入逻辑。
- [跨租户切换后续聊] → `GET /api/conversations/{id}/messages` 已按 `ent_code` 过滤，跨租户拉不到消息；新增 `ensureConversation` 校验会进一步把跨租户写入拦在防御层（SELECT 带 `ent_code` 条件）。

## Migration Plan

1. 先合入后端 `ensureConversation` 状态校验，作为防御层早于前端入口可用，不会因前端尚未发布而引发兼容问题。
2. 再合入前端历史列表「继续对话」按钮与 `continueConversation()` 函数，并把 `static/index.html` / `static/app.js` / `static/style.css` 的 `?v=N` 版本号 +1。
3. 回滚策略：单独回滚前端入口即可；后端 `ensureConversation` 状态校验作为通用防御保留，不影响其他链路。

## Open Questions

- 「继续对话」按钮的视觉定位（行内右侧 vs 详情面板顶部工具栏）由实现阶段评估对齐项目现有按钮风格后决定，不影响 spec 语义。
- 是否需要在 cancelled / error 历史消息上额外加「重试这条提问」入口，作为后续 change 单独评估，不在本变更范围。
