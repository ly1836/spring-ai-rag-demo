package com.example.rag.init;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ERP MySQL 初始化器测试。
 */
class ErpDatabaseInitializerTest {

	private JdbcTemplate erpJdbcTemplate;
	private ResourceLoader resourceLoader;
	private ErpDatabaseInitializer initializer;

	@BeforeEach
	public void setUp() {
		erpJdbcTemplate = mock(JdbcTemplate.class);
		resourceLoader = mock(ResourceLoader.class);
		initializer = new ErpDatabaseInitializer(erpJdbcTemplate, resourceLoader);
	}

	/**
	 * 验证 DDL 会直接执行，种子数据会按业务键跳过已存在记录。
	 */
	@Test
	public void shouldExecuteDdlAndOnlyInsertMissingRows() {
		String conversationScript = """
			CREATE TABLE IF NOT EXISTS a_billing_price_rule (
			    id BIGINT AUTO_INCREMENT PRIMARY KEY,
			    model VARCHAR(50) NOT NULL,
			    effective_date DATE NOT NULL,
			    remark VARCHAR(200)
			);

			INSERT INTO a_billing_price_rule (model, effective_date, remark) VALUES
			('deepseek-chat', '2026-01-01', '已有规则'),
			('qwen-max', '2026-01-01', '新增规则');
			""";
		String businessScript = """
			INSERT INTO b_sales_order (order_no, order_date, customer_name, ent_code) VALUES
			('SO20260301', '2026-03-01', '已存在客户', 'ENT001'),
			('SO20260309', '2026-03-29', '新增客户', 'ENT001');
			""";
		when(resourceLoader.getResource("classpath:db/init/conversation-billing-schema.sql"))
			.thenReturn(new ByteArrayResource(conversationScript.getBytes(StandardCharsets.UTF_8)));
		when(resourceLoader.getResource("classpath:db/init/business-data.sql"))
			.thenReturn(new ByteArrayResource(businessScript.getBytes(StandardCharsets.UTF_8)));
		when(erpJdbcTemplate.queryForObject(anyString(), eq(Integer.class),
			eq("deepseek-chat"), eq("2026-01-01"))).thenReturn(1);
		when(erpJdbcTemplate.queryForObject(anyString(), eq(Integer.class),
			eq("qwen-max"), eq("2026-01-01"))).thenReturn(0);
		when(erpJdbcTemplate.queryForObject(anyString(), eq(Integer.class),
			eq("SO20260301"))).thenReturn(1);
		when(erpJdbcTemplate.queryForObject(anyString(), eq(Integer.class),
			eq("SO20260309"))).thenReturn(0);

		initializer.initialize();

		verify(erpJdbcTemplate).execute("CREATE TABLE IF NOT EXISTS a_billing_price_rule (\n"
			+ "    id BIGINT AUTO_INCREMENT PRIMARY KEY,\n"
			+ "    model VARCHAR(50) NOT NULL,\n"
			+ "    effective_date DATE NOT NULL,\n"
			+ "    remark VARCHAR(200)\n"
			+ ")");
		verify(erpJdbcTemplate, never()).execute("INSERT INTO a_billing_price_rule (model, effective_date, remark) VALUES ('deepseek-chat', '2026-01-01', '已有规则')");
		verify(erpJdbcTemplate).execute("INSERT INTO a_billing_price_rule (model, effective_date, remark) VALUES ('qwen-max', '2026-01-01', '新增规则')");
		verify(erpJdbcTemplate, never()).execute("INSERT INTO b_sales_order (order_no, order_date, customer_name, ent_code) VALUES ('SO20260301', '2026-03-01', '已存在客户', 'ENT001')");
		verify(erpJdbcTemplate).execute("INSERT INTO b_sales_order (order_no, order_date, customer_name, ent_code) VALUES ('SO20260309', '2026-03-29', '新增客户', 'ENT001')");
	}

	/**
	 * 验证内置 SQL 脚本可完整解析，且每张种子数据表都有幂等键配置。
	 */
	@Test
	public void shouldParseBundledScriptsWithSeedKeyChecks() {
		JdbcTemplate jdbcTemplate = new JdbcTemplate() {
			@Override
			public void execute(String sql) {
				// 测试只验证脚本解析和幂等键覆盖，不连接真实数据库。
			}

			@Override
			public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
				return requiredType.cast(1);
			}
		};
		ErpDatabaseInitializer bundledInitializer = new ErpDatabaseInitializer(jdbcTemplate, new DefaultResourceLoader());

		bundledInitializer.initialize();
	}

}
