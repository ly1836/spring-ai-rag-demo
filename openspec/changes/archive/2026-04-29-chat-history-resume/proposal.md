## Why

涉及业务域：chat / conversation / vo

当前「历史记录」Tab 仅用于只读回放过往会话，用户想沿着旧会话继续提问时只能新建会话，丢失原有上下文。会话上下文由 `JdbcChatMemoryRepository` 基于 `a_chat_message` 表自动加载，后端天然支持「同一 conversationId 续聊」，缺的只是用户入口与状态衔接。同时，被软删除（`status = deleted`）的会话目前仍可被任意 conversationId 续写，会造成「幽灵消息」隐患，需要一并补防御层。

## What Changes

- 在「历史记录」列表中为每条会话增加「继续对话」入口，点击后切换到「AI 对话」Tab 并接续该会话。
- 「AI 对话」Tab 在续聊状态下：复用现有面板能力（流式输出、停止回答、模式切换、模型选择、计费状态），自动渲染历史消息为已存在气泡，并把 `currentConversationId` 替换为续聊会话 ID。
- 续聊时 `currentMode` 默认继承会话最后一条消息的 `mode`，但允许用户在面板上随时切换。
- 对软删除会话补后端防御层：`ChatHistoryService.ensureConversation()` 在写入前查询会话状态，对 `status = deleted` 的会话拒绝继续写入，抛 `IllegalStateException` → `BIZ_ERROR`。
- 当前正在流式输出时禁用「继续对话」入口，避免覆盖正在进行的会话。

## Capabilities

### New Capabilities
- `chat-history-resume`: 历史会话续聊能力，定义入口、状态衔接、模式继承与软删除会话拒绝续写的语义。

### Modified Capabilities

## Impact

- 前端历史记录与聊天面板：`src/main/resources/static/index.html`、`src/main/resources/static/app.js`、`src/main/resources/static/style.css`
- 会话写入防御层：`src/main/java/com/example/rag/conversation/ChatHistoryService.java`
- 续聊流程对外行为：复用现有 `GET /api/ask/stream`、`GET /api/conversations/{id}/messages`，无新增接口
- 计费与租户隔离：保持现有 `prepareConversation()` → `BillingService.checkQuota()` → `deductForTokenUsage()` 链路，不改变扣费语义
- ChatMemory 上下文窗口：沿用 `MessageWindowChatMemory.maxMessages = 20`，长会话续聊仅携带最近 N 条上下文
