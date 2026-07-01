package com.example.rag.tool.dynamic;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import org.springframework.stereotype.Component;

/**
 * 动态 SQL 模板命名参数绑定器。
 */
@Component
public class SqlTemplateBinder {

	/** SQL 模板中的 :name 命名参数匹配规则。 */
	private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("(?<!:):([A-Za-z_][A-Za-z0-9_]*)");

	/**
	 * 将 SQL 模板中的命名参数绑定为 JdbcTemplate 占位符。
	 *
	 * @param sqlTemplate SQL 模板
	 * @param inputSchema Tool 入参 JSON Schema
	 * @param toolInput   LLM 传入的 Tool 参数 JSON
	 * @return 绑定后的 SQL 和参数列表
	 */
	public BoundSql bind(String sqlTemplate, String inputSchema, String toolInput) {
		Set<String> declaredParameters = extractDeclaredParameters(inputSchema);
		Map<String, Object> arguments = parseArguments(toolInput);
		Matcher matcher = PLACEHOLDER_PATTERN.matcher(sqlTemplate);
		StringBuffer sql = new StringBuffer();
		List<Object> values = new ArrayList<>();
		while (matcher.find()) {
			String parameterName = matcher.group(1);
			if (!declaredParameters.contains(parameterName)) {
				throw new IllegalArgumentException("未声明的工具参数: " + parameterName);
			}
			if (!arguments.containsKey(parameterName)) {
				throw new IllegalArgumentException("缺少工具参数: " + parameterName);
			}
			matcher.appendReplacement(sql, Matcher.quoteReplacement("?"));
			values.add(arguments.get(parameterName));
		}
		matcher.appendTail(sql);
		return new BoundSql(sql.toString().trim(), values);
	}

	/**
	 * 从 JSON Schema 中提取允许的入参字段名。
	 *
	 * @param inputSchema Tool 入参 JSON Schema
	 * @return schema 声明的字段集合
	 */
	public Set<String> extractDeclaredParameters(String inputSchema) {
		try {
			JSONObject properties = JSON.parseObject(inputSchema).getJSONObject("properties");
			Set<String> names = new LinkedHashSet<>();
			if (properties != null) {
				Iterator<String> fieldNames = properties.keySet().iterator();
				while (fieldNames.hasNext()) {
					names.add(fieldNames.next());
				}
			}
			return names;
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Tool 入参 Schema 不是合法 JSON", ex);
		}
	}

	/**
	 * 解析 LLM 传入的 Tool 参数 JSON。
	 *
	 * @param toolInput Tool 参数 JSON
	 * @return 参数名到参数值的映射
	 */
	public Map<String, Object> parseArguments(String toolInput) {
		if (toolInput == null || toolInput.isBlank()) {
			return new LinkedHashMap<>();
		}
		try {
			return new LinkedHashMap<>(JSON.parseObject(toolInput));
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Tool 参数不是合法 JSON", ex);
		}
	}

}
