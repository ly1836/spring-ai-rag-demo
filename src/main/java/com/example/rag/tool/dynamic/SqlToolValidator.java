package com.example.rag.tool.dynamic;

import java.util.regex.Pattern;

import com.example.rag.dao.entity.LlmToolEntity;
import com.example.rag.tool.ToolNames;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import org.springframework.stereotype.Component;

/**
 * 动态 SQL Tool 安全校验器。
 */
@Component
public class SqlToolValidator {

	/** Tool 名称必须可被模型和 Spring AI 稳定识别。 */
	private static final Pattern TOOL_NAME_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");

	/** 主表别名只能是安全的 SQL 标识符。 */
	private static final Pattern TABLE_ALIAS_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

	/** 动态 SQL 禁止出现写操作或 DDL 关键字。 */
	private static final Pattern FORBIDDEN_SQL_PATTERN = Pattern.compile(
		"\\b(insert|update|delete|drop|alter|truncate|create|replace|merge|call|grant|revoke)\\b",
		Pattern.CASE_INSENSITIVE);

	/** 动态 SQL 入口必须是只读查询关键字。 */
	private static final Pattern READ_ONLY_SQL_PATTERN = Pattern.compile("^\\s*(select|with)\\b",
		Pattern.CASE_INSENSITIVE);

	/** 当前租户注入只支持单层 SELECT，复杂查询块先禁止配置。 */
	private static final Pattern UNSUPPORTED_MULTI_QUERY_PATTERN = Pattern.compile(
		"\\b(union|intersect|except)\\b|^\\s*with\\b|\\(\\s*select\\b", Pattern.CASE_INSENSITIVE);

	/**
	 * 校验完整的动态 Tool 配置。
	 *
	 * @param tool 动态 Tool 配置
	 */
	public void validateTool(LlmToolEntity tool) {
		if (tool == null) {
			throw new IllegalArgumentException("Tool 配置不能为空");
		}
		validateToolName(tool.getToolName());
		if (tool.getToolDesc() == null || tool.getToolDesc().isBlank()) {
			throw new IllegalArgumentException("Tool 描述不能为空");
		}
		validateInputSchema(tool.getInputSchema());
		validateSql(tool.getSqlTemplate());
		validateResultLimit(tool.getResultLimit());
		validateTableAlias(tool.getTableAlias());
	}

	/**
	 * 校验动态 SQL 只能是单条只读查询。
	 *
	 * @param sql SQL 模板
	 */
	public void validateSql(String sql) {
		if (sql == null || sql.isBlank()) {
			throw new IllegalArgumentException("SQL 模板不能为空");
		}
		String trimmed = sql.trim();
		if (trimmed.contains(";")) {
			throw new IllegalArgumentException("SQL 模板不允许包含分号");
		}
		if (!READ_ONLY_SQL_PATTERN.matcher(trimmed).find()) {
			throw new IllegalArgumentException("只允许配置只读查询 SQL");
		}
		if (FORBIDDEN_SQL_PATTERN.matcher(trimmed).find()) {
			throw new IllegalArgumentException("只允许配置只读查询 SQL");
		}
		validateTenantInjectionCompatibleSql(trimmed);
	}

	/**
	 * 校验 Tool 名称格式。
	 *
	 * @param toolName Tool 名称
	 */
	public void validateToolName(String toolName) {
		if (toolName == null || !TOOL_NAME_PATTERN.matcher(toolName).matches()) {
			throw new IllegalArgumentException("Tool 名称只能包含字母、数字和下划线，且必须以字母或下划线开头");
		}
		// 动态 Tool 不得占用系统内部 Tool 名称，避免 Provider 收到重复函数定义。
		if (ToolNames.CHART_PLAN.equals(toolName)) {
			throw new IllegalArgumentException("Tool 名称为系统保留名称");
		}
	}

	/**
	 * 校验 Tool 入参 JSON Schema。
	 *
	 * @param inputSchema 入参 JSON Schema
	 */
	public void validateInputSchema(String inputSchema) {
		if (inputSchema == null || inputSchema.isBlank()) {
			throw new IllegalArgumentException("Tool 入参 Schema 不能为空");
		}
		try {
			Object root = JSON.parse(inputSchema);
			if (!(root instanceof JSONObject)) {
				throw new IllegalArgumentException("Tool 入参 Schema 必须是 JSON 对象");
			}
		}
		catch (IllegalArgumentException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Tool 入参 Schema 不是合法 JSON", ex);
		}
	}

	/**
	 * 校验动态 Tool 返回行数上限。
	 *
	 * @param resultLimit 返回行数上限
	 */
	public void validateResultLimit(Integer resultLimit) {
		if (resultLimit != null && (resultLimit < 1 || resultLimit > 500)) {
			throw new IllegalArgumentException("Tool 返回行数必须在 1 到 500 之间");
		}
	}

	/**
	 * 校验动态 SQL 是否兼容当前租户条件注入方式。
	 *
	 * @param sql SQL 模板
	 */
	public void validateTenantInjectionCompatibleSql(String sql) {
		if (UNSUPPORTED_MULTI_QUERY_PATTERN.matcher(sql).find()) {
			throw new IllegalArgumentException("动态 SQL 暂不支持多查询块，请使用单层 SELECT 查询");
		}
	}

	/**
	 * 校验主表别名格式，避免别名拼接租户字段时引入非法 SQL 片段。
	 *
	 * @param tableAlias 主表别名
	 */
	public void validateTableAlias(String tableAlias) {
		if (tableAlias != null && !tableAlias.isBlank()
				&& !TABLE_ALIAS_PATTERN.matcher(tableAlias.trim()).matches()) {
			throw new IllegalArgumentException("主表别名只能包含字母、数字和下划线，且必须以字母或下划线开头");
		}
	}

}
