package com.example.rag.tool.dynamic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 动态 SQL 工具的命名参数绑定测试。
 */
public class SqlTemplateBinderTest {

	/**
	 * 验证声明过的命名参数会按 SQL 出现顺序绑定。
	 */
	@Test
	public void shouldBindNamedParametersByInputSchema() {
		SqlTemplateBinder binder = new SqlTemplateBinder();

		BoundSql result = binder.bind("""
			SELECT * FROM b_sales_order
			WHERE customer_name LIKE :customerName AND order_date >= :startDate
			""", """
			{
			  "type": "object",
			  "properties": {
			    "customerName": {"type": "string"},
			    "startDate": {"type": "string"}
			  }
			}
			""", "{\"customerName\":\"张三%\",\"startDate\":\"2026-01-01\"}");

		assertThat(result.sql()).contains("customer_name LIKE ? AND order_date >= ?");
		assertThat(result.arguments()).containsExactly("张三%", "2026-01-01");
	}

	/**
	 * 验证缺少必需参数时会拒绝执行。
	 */
	@Test
	public void shouldRejectMissingNamedParameter() {
		SqlTemplateBinder binder = new SqlTemplateBinder();

		assertThatThrownBy(() -> binder.bind("SELECT * FROM b_sales_order WHERE customer_name = :customerName",
			"""
			{
			  "type": "object",
			  "properties": {
			    "customerName": {"type": "string"}
			  }
			}
			""", "{}"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("缺少工具参数");
	}

	/**
	 * 验证 SQL 模板不能引用 schema 以外的参数。
	 */
	@Test
	public void shouldRejectUndeclaredPlaceholder() {
		SqlTemplateBinder binder = new SqlTemplateBinder();

		assertThatThrownBy(() -> binder.bind("SELECT * FROM b_sales_order WHERE customer_name = :customerName",
			"""
			{
			  "type": "object",
			  "properties": {}
			}
			""", "{\"customerName\":\"张三\"}"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("未声明的工具参数");
	}

}
