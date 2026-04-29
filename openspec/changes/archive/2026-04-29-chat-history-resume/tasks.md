## 1. 后端：软删除会话写入防御层

- [x] 1.1 在 `src/main/java/com/example/rag/conversation/ChatHistoryService.java` 的 `ensureConversation()` 内增加一次按主键 + `ent_code` 的状态查询，若查到 `status = 'deleted'` 抛 `IllegalStateException("会话已删除，不可继续")`，并补充中文方法注释说明新增校验
- [x] 1.2 验证现有 `prepareConversation()` 链路在校验失败时，由 `GlobalExceptionHandler` 统一返回 `BIZ_ERROR`，且 `a_chat_message` 不会沉淀任何「幽灵消息」
- [x] 1.3 手工验证：以正常会话 / 不存在会话 / 软删除会话三种 `conversationId` 调 `GET /api/ask/stream`，确认行为符合 spec 中三个 Scenario

## 2. 前端：历史列表入口

- [x] 2.1 在 `src/main/resources/static/index.html` 与 `style.css` 中为历史列表行新增「继续对话」按钮，视觉上与现有删除按钮（`btn-icon`）协调，并预留 `disabled` 态样式
- [x] 2.2 在 `src/main/resources/static/app.js` 的 `loadConversations()` 渲染逻辑中，根据会话 `status` 决定按钮是否可用，并通过 `event.stopPropagation()` 避免触发整行的查看详情逻辑
- [x] 2.3 流式输出进行中（`isStreaming === true`）时，新增「继续对话」按钮整体禁用或在点击时拦截并 toast 提示「请先停止当前回答」

## 3. 前端：续聊状态衔接

- [x] 3.1 在 `src/main/resources/static/app.js` 中新增 `continueConversation(conversationId)` 函数：拉取消息（复用 `GET /api/conversations/{id}/messages`）→ 切 Tab 到 `aiChat` → 重置 `chatMessages` 容器 → 写入 `currentConversationId` 与 `currentMode`（取消息列表最后一条 `mode`）→ 同步模式切换按钮 UI → 调用 `updateConversationTag()` 与底部会话标签
- [x] 3.2 在 `continueConversation()` 中按消息列表顺序复用现有 `addMessage()` / `renderAssistantBubble()` / `highlightCodeBlocks()` 渲染历史气泡，确保 `cancelled` / `error` 角标与「历史记录」Tab 完全一致
- [x] 3.3 完成后将 `chatMessages` 滚动到底部并把焦点交给输入框，验证后续提问以同一 `conversationId` 提交且历史上下文被 LLM 接续
- [x] 3.4 在「AI 对话」面板提供不刷新页面的新建对话/退出当前会话入口，清空当前 `conversationId` 与聊天容器并恢复欢迎态；流式回答进行中时禁止直接退出

## 4. 资源版本号与回归

- [x] 4.1 在 `src/main/resources/static/index.html` 中把 `app.js` / `style.css` 的 `?v=N` 版本号 +1，避免浏览器缓存掩盖前端改动
- [x] 4.2 手工验证三条主路径：新建会话 → 续聊已存在会话 → 续聊已被软删除的会话（被拒）；确认会话标识、模式继承、流式输出与停止能力均按 spec 表现
- [x] 4.3 执行 `mvn clean package` 与本地启动验证，确认聊天页面续聊功能可用且未破坏现有 `/api/ask`、`/api/ask/stream`、`/api/conversations`、`/api/conversations/{id}/messages` 行为
