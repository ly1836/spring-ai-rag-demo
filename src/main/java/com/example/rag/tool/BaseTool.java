package com.example.rag.tool;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.example.rag.config.TenantContext;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Tool 基类 — 封装多租户数据隔离逻辑。
 * <p>
 * 所有 ERP Tool 类继承此基类，通过 {@link #query(String, Object...)} 方法执行 SQL。
 * 该方法会自动在 SQL 末尾追加 AND ent_code = ? 条件，并将当前租户的 ent_code 作为参数传入。
 * <p>
 * 使用约定：
 * - SQL 中必须已有 WHERE 子句（至少 WHERE 1=1）
 * - 基类会自动追加 " AND ent_code = ?" 和对应参数
 * <p>
 * 示例：
 * <pre>
 * query("SELECT * FROM sales_order WHERE customer_name LIKE ?", "%张三%")
 * // 实际执行：SELECT * FROM sales_order WHERE customer_name LIKE ? AND ent_code = ?
 * // 参数：["%张三%", "当前租户ent_code"]
 * </pre>
 */
public abstract class BaseTool {

	protected final JdbcTemplate erp;

	protected BaseTool(JdbcTemplate erpJdbcTemplate) {
		this.erp = erpJdbcTemplate;
	}

	/**
	 * 执行带租户隔离的查询。
	 * 自动在 SQL 末尾追加 ent_code 条件，无需每个 Tool 方法手动处理。
	 *
	 * @param sql    SQL 语句（必须包含 WHERE 子句）
	 * @param params 原始查询参数（不含 ent_code）
	 * @return 查询结果列表
	 */
	protected List<Map<String, Object>> query(String sql, Object... params) {
		return queryWithAlias(null, sql, params);
	}

	/**
	 * 执行带租户隔离的查询（可指定表别名）。
	 * 用于 JOIN 查询场景，避免 ent_code 列歧义。
	 * <p>
	 * 注意：不可命名为 query(String, String, Object...) —— Java varargs 重载解析会在
	 * 所有参数均为 String 时优先匹配 (String, String, Object...) 而非 (String, Object...)，
	 * 导致 SQL 和参数错位。
	 *
	 * @param tableAlias 主表别名（如 "o"），为 null 时不加前缀
	 * @param sql        SQL 语句（必须包含 WHERE 子句）
	 * @param params     原始查询参数（不含 ent_code）
	 * @return 查询结果列表
	 */
	protected List<Map<String, Object>> queryWithAlias(String tableAlias, String sql, Object... params) {
		String entCode = TenantContext.requireEntCode();

		String column = (tableAlias != null && !tableAlias.isBlank())
			? tableAlias + ".ent_code" : "ent_code";
		String tenantSql = injectEntCodeCondition(sql, column);

		List<Object> allParams = new ArrayList<>(Arrays.asList(params));
		allParams.add(entCode);

		return erp.queryForList(tenantSql, allParams.toArray());
	}

	/**
	 * 在 SQL 的 WHERE 子句末尾（ORDER BY / LIMIT / GROUP BY 之前）插入 ent_code 条件。
	 */
	private String injectEntCodeCondition(String sql, String column) {
		String upper = sql.toUpperCase();
		String condition = " AND " + column + " = ?";

		int insertPos = findInsertPosition(upper);
		if (insertPos >= 0) {
			return sql.substring(0, insertPos) + condition + " " + sql.substring(insertPos);
		}

		return sql + condition;
	}

	/**
	 * 查找 SQL 中 ORDER BY / GROUP BY / LIMIT 的最早出现位置。
	 * 返回应该插入 ent_code 条件的位置（在这些关键字之前）。
	 */
	private int findInsertPosition(String upperSql) {
		int[] positions = {
			upperSql.lastIndexOf("ORDER BY"),
			upperSql.lastIndexOf("GROUP BY"),
			upperSql.lastIndexOf("LIMIT")
		};

		int earliest = -1;
		for (int pos : positions) {
			if (pos >= 0 && (earliest < 0 || pos < earliest)) {
				earliest = pos;
			}
		}
		return earliest;
	}

}
