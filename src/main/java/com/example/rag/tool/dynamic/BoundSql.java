package com.example.rag.tool.dynamic;

import java.util.List;

/**
 * 动态 SQL 绑定结果。
 *
 * @param sql       绑定后的 SQL
 * @param arguments 按占位符顺序排列的参数
 */
public record BoundSql(String sql, List<Object> arguments) {

	/**
	 * 创建不可变的动态 SQL 绑定结果。
	 *
	 * @param sql       绑定后的 SQL
	 * @param arguments 按占位符顺序排列的参数
	 */
	public BoundSql {
		arguments = List.copyOf(arguments);
	}
}
