package com.example.rag.chat.dto;

import com.example.rag.vo.ChartVO;

/**
 * 助手消息保存结果。
 *
 * @param messageId 助手消息 ID
 * @param chart     实际成功持久化的图表
 */
public record SavedAssistantMessage(String messageId, ChartVO.ChartSpec chart) {
}
