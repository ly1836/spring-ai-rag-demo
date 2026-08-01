package com.example.rag.chat.chart.model;

import java.time.Instant;
import java.util.List;

import com.example.rag.vo.ChartVO;

/**
 * 单次问答链路的图表上下文快照。
 *
 * @param traceId        问答链路 ID
 * @param entCode        租户编码
 * @param conversationId 会话 ID
 * @param results        按完成顺序保存的业务 Tool 结果
 * @param chart          首个通过完整校验的图表
 * @param createdAt      上下文创建时间
 */
public record TraceChartContext(String traceId, String entCode, String conversationId,
		List<BusinessToolResult> results, ChartVO.ChartSpec chart, Instant createdAt) {

	/**
	 * 创建不可变图表上下文。
	 */
	public TraceChartContext {
		results = List.copyOf(results);
	}

}
