package com.example.rag.tool.dynamic;

import java.util.List;
import java.util.Map;

/**
 * 数据库动态 Tool 执行结果。
 *
 * @param rows 查询返回的数据行
 */
public record DatabaseToolResult(List<Map<String, Object>> rows) {

	/**
	 * 创建不可变的数据库动态 Tool 执行结果。
	 *
	 * @param rows 查询返回的数据行
	 */
	public DatabaseToolResult {
		rows = List.copyOf(rows);
	}
}
