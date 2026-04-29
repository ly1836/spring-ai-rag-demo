## 1. 前端停止交互

- [x] 1.1 在 `src/main/resources/static/index.html` 与 `style.css` 中为聊天输入区增加“停止回答”按钮和流式进行中的视觉状态
- [x] 1.2 在 `src/main/resources/static/app.js` 中引入当前流请求控制器与取消标记，使用 `AbortController` 中止 `GET /api/ask/stream`
- [x] 1.3 调整前端流式状态机，区分正常完成、用户取消、请求失败，确保取消后保留部分回答且不展示通用失败提示

## 2. 后端取消语义与持久化

- [x] 2.1 在 `src/main/java/com/example/rag/chat/ErpAssistantService.java` 中按 Reactor 终止信号区分 `cancel` 与正常完成，并为取消态走单独录制分支
- [x] 2.2 在 `src/main/java/com/example/rag/conversation/ChatHistoryService.java` 中扩展助手消息保存能力，支持把流式取消结果保存为 `cancelled` 状态且保持会话统计兼容
- [x] 2.3 校准取消场景下的 token 结算逻辑，确保仅按已观察到的 usage 结算且不会触发重复扣费

## 3. 历史展示与回归验证

- [x] 3.1 调整历史消息展示相关前端或 VO 映射，使 `cancelled` 状态可被识别且不会误显示为成功或错误
- [x] 3.2 手工验证流式问答的正常完成、主动终止、异常失败三条路径，确认消息状态、部分内容保留和计费行为符合 spec
- [x] 3.3 执行项目编译与启动验证，确认聊天页面停止回答功能可用且未破坏现有 `/api/ask`、`/api/ask/stream` 行为
