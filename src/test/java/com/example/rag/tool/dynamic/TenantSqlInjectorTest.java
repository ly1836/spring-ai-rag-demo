package com.example.rag.tool.dynamic;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 动态 SQL 工具的租户条件注入测试。
 */
public class TenantSqlInjectorTest {

	/**
	 * 验证普通 WHERE 查询会在排序之前追加租户条件。
	 */
	@Test
	public void shouldInjectTenantConditionBeforeOrderBy() {
		TenantSqlInjector injector = new TenantSqlInjector();

		String result = injector.inject("SELECT * FROM b_sales_order WHERE customer_name = ? ORDER BY order_date DESC",
			null);

		assertThat(result)
			.isEqualTo("SELECT * FROM b_sales_order WHERE (customer_name = ?) AND ent_code = ? ORDER BY order_date DESC");
	}

	/**
	 * 验证存在 OR 条件时会包裹原 WHERE 条件，避免租户条件只作用在 OR 右侧。
	 */
	@Test
	public void shouldWrapExistingWhereConditionWhenSqlContainsOr() {
		TenantSqlInjector injector = new TenantSqlInjector();

		TenantSqlInjection result = injector.injectWithParameterIndex(
			"SELECT * FROM b_sales_order o WHERE customer_name = ? OR status = ? ORDER BY order_date DESC", "o");

		assertThat(result.sql())
			.isEqualTo("SELECT * FROM b_sales_order o WHERE (customer_name = ? OR status = ?) AND o.ent_code = ? ORDER BY order_date DESC");
		assertThat(result.tenantParameterIndex()).isEqualTo(2);
	}

	/**
	 * 验证无 WHERE 查询会补齐 WHERE 并在分页之前追加租户条件。
	 */
	@Test
	public void shouldAddWhereConditionBeforeLimit() {
		TenantSqlInjector injector = new TenantSqlInjector();

		String result = injector.inject("SELECT * FROM b_inventory LIMIT 20", null);

		assertThat(result)
			.isEqualTo("SELECT * FROM b_inventory WHERE ent_code = ? LIMIT 20");
	}

	/**
	 * 验证配置表别名时会使用别名限定 ent_code 字段。
	 */
	@Test
	public void shouldInjectTenantConditionWithTableAlias() {
		TenantSqlInjector injector = new TenantSqlInjector();

		String result = injector.inject("SELECT * FROM b_sales_order o WHERE o.customer_name = ? LIMIT 20", "o");

		assertThat(result)
			.isEqualTo("SELECT * FROM b_sales_order o WHERE (o.customer_name = ?) AND o.ent_code = ? LIMIT 20");
	}

}
