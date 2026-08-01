package com.example.rag.chat.dto;

/**
 * 流式问答内部事件帧。
 *
 * @param event SSE 事件类型
 * @param data  类型化事件数据
 */
public record ChatStreamFrame(String event, Object data) {
}
