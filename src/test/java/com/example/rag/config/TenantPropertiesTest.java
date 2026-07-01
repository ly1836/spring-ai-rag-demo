package com.example.rag.config;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 租户隔离配置测试。
 */
class TenantPropertiesTest {

	/**
	 * 验证忽略表名支持大小写不敏感匹配。
	 */
	@Test
	void shouldIgnoreConfiguredTablesIgnoringCase() {
		TenantProperties properties = new TenantProperties();
		properties.setIgnoreTables(List.of("a_billing_plan", "A_BILLING_PRICE_RULE"));

		assertThat(properties.isIgnoredTable("A_BILLING_PLAN")).isTrue();
		assertThat(properties.isIgnoredTable("a_billing_price_rule")).isTrue();
		assertThat(properties.isIgnoredTable("a_chat_message")).isFalse();
	}

	/**
	 * 验证空表名不会被错误忽略。
	 */
	@Test
	void shouldNotIgnoreBlankTableName() {
		TenantProperties properties = new TenantProperties();
		properties.setIgnoreTables(List.of("a_billing_plan"));

		assertThat(properties.isIgnoredTable(null)).isFalse();
		assertThat(properties.isIgnoredTable(" ")).isFalse();
	}

	/**
	 * 验证动态 Tool 定义表是全局表，调用日志表仍按租户隔离。
	 */
	@Test
	public void shouldIgnoreLlmToolButNotToolCallLogByDefault() {
		TenantProperties properties = new TenantProperties();

		assertThat(properties.isIgnoredTable("a_llm_tool")).isTrue();
		assertThat(properties.isIgnoredTable("a_tool_call_log")).isFalse();
	}
}
