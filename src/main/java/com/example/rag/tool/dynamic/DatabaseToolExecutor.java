package com.example.rag.tool.dynamic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.example.rag.config.TenantContext;
import com.example.rag.dao.entity.LlmToolEntity;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 数据库动态 Tool 执行器。
 */
@Component
public class DatabaseToolExecutor {

	/** 判断 SQL 是否已经包含 LIMIT 子句。 */
	private static final Pattern LIMIT_PATTERN = Pattern.compile("\\bLIMIT\\b", Pattern.CASE_INSENSITIVE);

	/** ERP MySQL 查询入口。 */
	private final JdbcTemplate erpJdbcTemplate;

	/** 动态 SQL 安全校验器。 */
	private final SqlToolValidator validator;

	/** SQL 命名参数绑定器。 */
	private final SqlTemplateBinder binder;

	/** 租户条件注入器。 */
	private final TenantSqlInjector tenantSqlInjector;

	/**
	 * 创建数据库动态 Tool 执行器。
	 *
	 * @param erpJdbcTemplate  ERP MySQL JdbcTemplate
	 * @param validator        动态 SQL 安全校验器
	 * @param binder           SQL 命名参数绑定器
	 * @param tenantSqlInjector 租户条件注入器
	 */
	public DatabaseToolExecutor(@Qualifier("erpJdbcTemplate") JdbcTemplate erpJdbcTemplate,
			SqlToolValidator validator, SqlTemplateBinder binder, TenantSqlInjector tenantSqlInjector) {
		this.erpJdbcTemplate = erpJdbcTemplate;
		this.validator = validator;
		this.binder = binder;
		this.tenantSqlInjector = tenantSqlInjector;
	}

	/**
	 * 执行数据库动态 Tool 查询。
	 *
	 * @param tool      动态 Tool 配置
	 * @param toolInput LLM 传入的 Tool 参数 JSON
	 * @return 数据库查询结果
	 */
	public DatabaseToolResult execute(LlmToolEntity tool, String toolInput) {
		validator.validateTool(tool);
		String entCode = TenantContext.requireEntCode();
		BoundSql boundSql = binder.bind(tool.getSqlTemplate(), tool.getInputSchema(), toolInput);
		TenantSqlInjection injection = tenantSqlInjector.injectWithParameterIndex(boundSql.sql(), tool.getTableAlias());
		String limitedSql = applyResultLimit(injection.sql(), tool.getResultLimit());
		List<Object> arguments = new ArrayList<>(boundSql.arguments());
		arguments.add(injection.tenantParameterIndex(), entCode);
		List<Map<String, Object>> rows = erpJdbcTemplate.queryForList(limitedSql, arguments.toArray());
		return new DatabaseToolResult(rows);
	}

	/**
	 * 在未显式配置 LIMIT 时追加默认返回行数限制。
	 *
	 * @param sql         SQL 文本
	 * @param resultLimit 返回行数限制
	 * @return 带 LIMIT 的 SQL
	 */
	public String applyResultLimit(String sql, Integer resultLimit) {
		int limit = resultLimit == null ? 50 : resultLimit;
		if (LIMIT_PATTERN.matcher(sql).find()) {
			return "SELECT * FROM (" + sql + ") dynamic_tool_result LIMIT " + limit;
		}
		return sql + " LIMIT " + limit;
	}

}
