## Why

涉及业务域：chat / conversation / billing / vo

当前聊天页面在 `GET /api/ask/stream` 流式输出期间只支持等待模型自然结束，用户无法主动终止正在生成的回答。对于长回答、误触发问题或工具调用耗时较长的场景，这会造成较差的交互体验，也使前端无法把“用户主动停止”与“请求失败”区分开。

## What Changes

- 为聊天页面增加“停止回答”交互，在流式回答进行中允许用户主动终止当前 SSE 请求。
- 调整前端流式问答状态机，区分“正常完成”“用户取消”“请求失败”三种结束方式，避免取消后展示通用失败提示。
- 调整后端流式录制逻辑，将用户取消识别为独立终态，保留已生成的部分回答并写入可识别的取消状态。
- 明确取消场景下的历史记录与计费行为，保证不会把用户主动停止误记为系统异常或重复扣费。

## Capabilities

### New Capabilities
- `chat-streaming-cancellation`: 为流式问答提供用户主动终止、取消态持久化和 UI 状态反馈能力。

### Modified Capabilities

## Impact

- 前端聊天页面：`src/main/resources/static/index.html`、`src/main/resources/static/app.js`、`src/main/resources/static/style.css`
- 流式问答编排：`src/main/java/com/example/rag/chat/ChatController.java`、`src/main/java/com/example/rag/chat/ErpAssistantService.java`
- 历史消息持久化与展示：`src/main/java/com/example/rag/conversation/ChatHistoryService.java`、相关 VO
- 流式问答对外行为：`GET /api/ask/stream`
- 计费与消息状态语义：取消态下的消息状态、token 记录与扣费边界
