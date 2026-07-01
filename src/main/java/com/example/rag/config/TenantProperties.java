package com.example.rag.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 多租户 SQL 隔离配置。
 * <p>
 * 绑定 {@code app.tenant} 配置，用于 MyBatis-Plus 租户插件判断租户字段名和需要忽略的全局表。
 */
@ConfigurationProperties(prefix = "app.tenant")
public class TenantProperties {

	private String column = "ent_code";

	private List<String> ignoreTables = new ArrayList<>(Arrays.asList("a_billing_plan", "a_billing_price_rule",
		"a_llm_tool"));

	public String getColumn() {
		return column;
	}

	public void setColumn(String column) {
		this.column = (column == null || column.isBlank()) ? "ent_code" : column;
	}

	public List<String> getIgnoreTables() {
		return ignoreTables;
	}

	public void setIgnoreTables(List<String> ignoreTables) {
		this.ignoreTables = ignoreTables == null ? new ArrayList<>() : new ArrayList<>(ignoreTables);
	}

	/**
	 * 判断指定表是否跳过租户隔离。
	 *
	 * @param tableName SQL 中解析出的表名
	 * @return true 表示跳过租户条件注入
	 */
	public boolean isIgnoredTable(String tableName) {
		if (tableName == null || tableName.isBlank()) {
			return false;
		}
		if (ignoreTables == null || ignoreTables.isEmpty()) {
			return false;
		}
		String normalized = normalize(tableName);
		return ignoreTables.stream()
			.map(this::normalize)
			.anyMatch(normalized::equals);
	}

	private String normalize(String tableName) {
		return tableName.trim().toLowerCase(Locale.ROOT);
	}

}
