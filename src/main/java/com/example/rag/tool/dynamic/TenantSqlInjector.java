package com.example.rag.tool.dynamic;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * 动态 SQL 租户条件注入器。
 */
@Component
public class TenantSqlInjector {

	/** SQL 尾部子句匹配规则，租户条件需要插入到这些子句之前。 */
	private static final Pattern TAIL_CLAUSE_PATTERN = Pattern.compile("\\b(ORDER\\s+BY|GROUP\\s+BY|LIMIT)\\b",
		Pattern.CASE_INSENSITIVE);

	/** WHERE 子句匹配规则，用于判断是否需要补齐 WHERE。 */
	private static final Pattern WHERE_PATTERN = Pattern.compile("\\bWHERE\\b", Pattern.CASE_INSENSITIVE);

	/**
	 * 向 SQL 中追加 ent_code 租户条件。
	 *
	 * @param sql        已绑定参数的 SQL
	 * @param tableAlias 主表别名
	 * @return 带租户条件的 SQL
	 */
	public String inject(String sql, String tableAlias) {
		return injectWithParameterIndex(sql, tableAlias).sql();
	}

	/**
	 * 判断 SQL 片段中是否已有 WHERE 子句。
	 *
	 * @param sql SQL 片段
	 * @return true 表示已有 WHERE
	 */
	public boolean hasWhereClause(String sql) {
		return WHERE_PATTERN.matcher(sql).find();
	}

	/**
	 * 压缩 SQL 空白字符，避免多行模板影响后续拼接。
	 *
	 * @param sql 原始 SQL
	 * @return 单行 SQL
	 */
	public String normalizeWhitespace(String sql) {
		return sql.trim().replaceAll("\\s+", " ");
	}

	/**
	 * 查找最早的尾部子句位置。
	 *
	 * @param sql SQL 文本
	 * @return 尾部子句位置，不存在时返回 -1
	 */
	public int findTailClausePosition(String sql) {
		Matcher matcher = TAIL_CLAUSE_PATTERN.matcher(sql.toUpperCase(Locale.ROOT));
		return matcher.find() ? matcher.start() : -1;
	}

	/**
	 * 向 SQL 中追加 ent_code 租户条件，并返回租户参数所在位置。
	 *
	 * @param sql        已绑定参数的 SQL
	 * @param tableAlias 主表别名
	 * @return 租户条件注入结果
	 */
	public TenantSqlInjection injectWithParameterIndex(String sql, String tableAlias) {
		String normalizedSql = normalizeWhitespace(sql);
		String column = (tableAlias != null && !tableAlias.isBlank()) ? tableAlias.trim() + ".ent_code" : "ent_code";
		int insertPosition = findTailClausePosition(normalizedSql);
		String prefix = insertPosition >= 0 ? normalizedSql.substring(0, insertPosition).stripTrailing() : normalizedSql;
		String suffix = insertPosition >= 0 ? normalizedSql.substring(insertPosition).stripLeading() : "";
		Matcher whereMatcher = WHERE_PATTERN.matcher(prefix);
		String tenantSql;
		if (whereMatcher.find()) {
			// 包裹原 WHERE 条件，避免 OR 优先级绕过租户条件。
			String selectPart = prefix.substring(0, whereMatcher.start()).stripTrailing();
			String whereCondition = prefix.substring(whereMatcher.end()).strip();
			tenantSql = selectPart + " WHERE (" + whereCondition + ") AND " + column + " = ?";
		}
		else {
			tenantSql = prefix + " WHERE " + column + " = ?";
		}
		tenantSql = suffix.isBlank() ? tenantSql : tenantSql + " " + suffix;
		return new TenantSqlInjection(tenantSql, countJdbcParameters(prefix));
	}

	/**
	 * 统计 SQL 片段中的 JDBC 参数占位符数量，忽略字符串字面量中的问号。
	 *
	 * @param sql SQL 片段
	 * @return JDBC 参数占位符数量
	 */
	public int countJdbcParameters(String sql) {
		int count = 0;
		boolean inQuote = false;
		for (int i = 0; i < sql.length(); i++) {
			char ch = sql.charAt(i);
			if (ch == '\'') {
				if (inQuote && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
					i++;
					continue;
				}
				inQuote = !inQuote;
			}
			if (!inQuote && ch == '?') {
				count++;
			}
		}
		return count;
	}

}
