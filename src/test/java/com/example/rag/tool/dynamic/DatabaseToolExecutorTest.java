package com.example.rag.tool.dynamic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.example.rag.config.TenantContext;
import com.example.rag.dao.entity.LlmToolEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 数据库动态 Tool 执行器测试。
 */
public class DatabaseToolExecutorTest {

	/**
	 * 清理测试线程中的租户上下文。
	 */
	@AfterEach
	public void tearDown() {
		TenantContext.clear();
	}

	/**
	 * 验证执行 SQL 时会绑定业务参数并追加当前租户编码。
	 */
	@Test
	public void shouldAppendTenantArgumentWhenExecutingDynamicTool() {
		JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
		when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
			.thenReturn(List.of(Map.of("customer_name", "张三")));
		DatabaseToolExecutor executor = new DatabaseToolExecutor(jdbcTemplate, new SqlToolValidator(),
			new SqlTemplateBinder(), new TenantSqlInjector());
		LlmToolEntity tool = buildTool();
		TenantContext.setEntCode("ENT-001");

		DatabaseToolResult result = executor.execute(tool, "{\"customerName\":\"张三\"}");

		ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
		verify(jdbcTemplate).queryForList(sqlCaptor.capture(), argsCaptor.capture());
		assertThat(sqlCaptor.getValue()).isEqualTo(
			"SELECT * FROM (SELECT * FROM b_sales_order o WHERE (customer_name = ?) AND o.ent_code = ? LIMIT 10) dynamic_tool_result LIMIT 10");
		assertThat(argsCaptor.getValue()).containsExactly("张三", "ENT-001");
		assertThat(result.rows()).containsExactly(Map.of("customer_name", "张三"));
	}

	/**
	 * 验证缺少租户上下文时拒绝执行动态 Tool。
	 */
	@Test
	public void shouldRejectExecutionWithoutTenantContext() {
		JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
		DatabaseToolExecutor executor = new DatabaseToolExecutor(jdbcTemplate, new SqlToolValidator(),
			new SqlTemplateBinder(), new TenantSqlInjector());

		assertThatThrownBy(() -> executor.execute(buildTool(), "{\"customerName\":\"张三\"}"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("租户");
	}

	/**
	 * 验证 SQL 未显式配置 LIMIT 时会追加返回行数限制。
	 */
	@Test
	public void shouldApplyResultLimitWhenSqlHasNoLimit() {
		JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
		when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
		DatabaseToolExecutor executor = new DatabaseToolExecutor(jdbcTemplate, new SqlToolValidator(),
			new SqlTemplateBinder(), new TenantSqlInjector());
		LlmToolEntity tool = buildTool();
		tool.setSqlTemplate("SELECT * FROM b_sales_order o WHERE customer_name = :customerName");
		tool.setResultLimit(7);
		TenantContext.setEntCode("ENT-001");

		executor.execute(tool, "{\"customerName\":\"张三\"}");

		ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		verify(jdbcTemplate).queryForList(sqlCaptor.capture(), any(Object[].class));
		assertThat(sqlCaptor.getValue()).endsWith("LIMIT 7");
	}

	/**
	 * 验证尾部子句中存在参数时，租户参数仍绑定到租户条件位置。
	 */
	@Test
	public void shouldInsertTenantArgumentBeforeTailClauseArguments() {
		JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
		when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
		DatabaseToolExecutor executor = new DatabaseToolExecutor(jdbcTemplate, new SqlToolValidator(),
			new SqlTemplateBinder(), new TenantSqlInjector());
		LlmToolEntity tool = buildTool();
		tool.setInputSchema("""
			{
			  "type": "object",
			  "properties": {
			    "customerName": {"type": "string"},
			    "sortField": {"type": "string"},
			    "limit": {"type": "integer"}
			  }
			}
			""");
		tool.setSqlTemplate("""
			SELECT * FROM b_sales_order o
			WHERE customer_name = :customerName
			ORDER BY CASE WHEN :sortField = 'date' THEN o.order_date ELSE o.created_at END DESC
			LIMIT :limit
			""");
		TenantContext.setEntCode("ENT-001");

		executor.execute(tool, "{\"customerName\":\"张三\",\"sortField\":\"date\",\"limit\":10}");

		ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
		verify(jdbcTemplate).queryForList(anyString(), argsCaptor.capture());
		assertThat(argsCaptor.getValue()).containsExactly("张三", "ENT-001", "date", 10);
	}

	/**
	 * 验证 SQL 自带 LIMIT 时仍会被外层 LIMIT 兜底限制。
	 */
	@Test
	public void shouldWrapSqlWithConfiguredLimitWhenSqlAlreadyHasLimit() {
		JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
		when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
		DatabaseToolExecutor executor = new DatabaseToolExecutor(jdbcTemplate, new SqlToolValidator(),
			new SqlTemplateBinder(), new TenantSqlInjector());
		LlmToolEntity tool = buildTool();
		tool.setSqlTemplate("SELECT * FROM b_sales_order o WHERE customer_name = :customerName LIMIT 1000");
		tool.setResultLimit(7);
		TenantContext.setEntCode("ENT-001");

		executor.execute(tool, "{\"customerName\":\"张三\"}");

		ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		verify(jdbcTemplate).queryForList(sqlCaptor.capture(), any(Object[].class));
		assertThat(sqlCaptor.getValue()).isEqualTo(
			"SELECT * FROM (SELECT * FROM b_sales_order o WHERE (customer_name = ?) AND o.ent_code = ? LIMIT 1000) dynamic_tool_result LIMIT 7");
	}

	/**
	 * 构造测试用动态 Tool 配置。
	 */
	private LlmToolEntity buildTool() {
		LlmToolEntity tool = new LlmToolEntity();
		tool.setToolName("query_sales_order");
		tool.setToolDesc("查询销售订单");
		tool.setInputSchema("""
			{
			  "type": "object",
			  "properties": {
			    "customerName": {"type": "string"}
			  }
			}
			""");
		tool.setSqlTemplate("SELECT * FROM b_sales_order o WHERE customer_name = :customerName LIMIT 10");
		tool.setTableAlias("o");
		tool.setResultLimit(10);
		return tool;
	}

}
