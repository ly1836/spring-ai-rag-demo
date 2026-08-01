package com.example.rag.chat.dto;

import com.example.rag.vo.ChartVO;

/**
 * LLM 图表选择结果，只允许图表类型和业务标题。
 *
 * @param type  图表类型
 * @param title 业务标题
 */
public record ChartSelection(ChartVO.ChartType type, String title) {
}
