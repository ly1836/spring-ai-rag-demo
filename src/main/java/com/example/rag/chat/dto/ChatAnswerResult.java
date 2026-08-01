package com.example.rag.chat.dto;

import com.example.rag.vo.ChartVO;

/**
 * 非流式回答内部结果。
 *
 * @param answer 回答文本
 * @param chart  可空图表
 */
public record ChatAnswerResult(String answer, ChartVO.ChartSpec chart) {
}
