package com.example.rag.chat.chart.model;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本轮成功业务 Tool 的可图表化结果快照。
 *
 * @param sequence   Tool 完成顺序，从 1 开始
 * @param toolName   Tool 名称
 * @param toolType   Tool 来源类型
 * @param rows       安全解析后的数据行
 * @param capturedAt 捕获时间
 */
public record BusinessToolResult(int sequence, String toolName, String toolType,
		List<Map<String, Object>> rows, Instant capturedAt) {

	/**
	 * 创建不可变业务 Tool 结果。
	 */
	public BusinessToolResult {
		rows = rows.stream()
			.map(row -> Collections.unmodifiableMap(new LinkedHashMap<>(row)))
			.toList();
	}

}
