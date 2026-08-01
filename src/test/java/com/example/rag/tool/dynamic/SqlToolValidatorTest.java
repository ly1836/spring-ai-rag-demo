package com.example.rag.tool.dynamic;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.example.rag.dao.entity.LlmToolEntity;
import com.example.rag.tool.ToolNames;
import org.junit.jupiter.api.Test;

/**
 * 动态 SQL 工具的安全校验测试。
 */
public class SqlToolValidatorTest {

	/**
	 * 验证只读 SELECT SQL 可以通过校验。
	 */
	@Test
	public void shouldAcceptReadOnlySelectSql() {
		SqlToolValidator validator = new SqlToolValidator();

		validator.validateSql("SELECT id, customer_name FROM b_sales_order WHERE customer_name = :customerName");
	}

	/**
	 * 验证 SELECT 后换行的只读 SQL 可以通过校验。
	 */
	@Test
	public void shouldAcceptMultilineSelectSql() {
		SqlToolValidator validator = new SqlToolValidator();

		validator.validateSql("""
			SELECT
			  id,
			  customer_name
			FROM b_sales_order
			""");
	}

	/**
	 * 验证写操作 SQL 会被拒绝。
	 */
	@Test
	public void shouldRejectWriteSql() {
		SqlToolValidator validator = new SqlToolValidator();

		assertThatThrownBy(() -> validator.validateSql("UPDATE b_sales_order SET status = 'done'"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("只允许配置只读查询");
	}

	/**
	 * 验证多语句 SQL 会被拒绝。
	 */
	@Test
	public void shouldRejectMultipleStatements() {
		SqlToolValidator validator = new SqlToolValidator();

		assertThatThrownBy(() -> validator.validateSql("SELECT * FROM b_sales_order; SELECT * FROM b_customer"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("不允许包含分号");
	}

	/**
	 * 验证多查询块 SQL 会被拒绝，避免只给部分查询追加租户条件。
	 */
	@Test
	public void shouldRejectSetOperationSql() {
		SqlToolValidator validator = new SqlToolValidator();

		assertThatThrownBy(() -> validator.validateSql(
			"SELECT * FROM b_sales_order UNION SELECT * FROM b_purchase_order"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("暂不支持多查询块");
	}

	/**
	 * 验证 CTE SQL 会被拒绝，避免租户条件注入到错误查询层级。
	 */
	@Test
	public void shouldRejectCteSql() {
		SqlToolValidator validator = new SqlToolValidator();

		assertThatThrownBy(() -> validator.validateSql(
			"WITH recent AS (SELECT * FROM b_sales_order) SELECT * FROM recent"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("暂不支持多查询块");
	}

	/**
	 * 验证子查询 SQL 会被拒绝，避免内层查询绕过租户隔离。
	 */
	@Test
	public void shouldRejectSubquerySql() {
		SqlToolValidator validator = new SqlToolValidator();

		assertThatThrownBy(() -> validator.validateSql(
			"SELECT * FROM b_sales_order WHERE customer_id IN (SELECT id FROM b_customer)"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("暂不支持多查询块");
	}

	/**
	 * 验证合法主表别名可以通过完整 Tool 校验。
	 */
	@Test
	public void shouldAcceptValidTableAlias() {
		SqlToolValidator validator = new SqlToolValidator();

		assertThatCode(() -> validator.validateTool(buildTool("o_1")))
			.doesNotThrowAnyException();
	}

	/**
	 * 验证非法主表别名会被拒绝，避免拼接租户条件时引入非法 SQL 片段。
	 */
	@Test
	public void shouldRejectInvalidTableAlias() {
		SqlToolValidator validator = new SqlToolValidator();

		assertThatThrownBy(() -> validator.validateTool(buildTool("o;drop")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("主表别名只能包含字母、数字和下划线");
	}

	/**
	 * 构造测试用动态 Tool 配置。
	 *
	 * @param tableAlias 主表别名
	 * @return 动态 Tool 配置
	 */
	private LlmToolEntity buildTool(String tableAlias) {
		LlmToolEntity tool = new LlmToolEntity();
		tool.setToolName("query_order");
		tool.setToolDesc("查询销售订单");
		tool.setInputSchema("""
			{
			  "type": "object",
			  "properties": {}
			}
			""");
		tool.setSqlTemplate("SELECT * FROM b_sales_order o");
		tool.setTableAlias(tableAlias);
		tool.setResultLimit(50);
		return tool;
	}

	/**
	 * 验证动态 Tool 不得占用内部图表规划 Tool 的系统保留名称。
	 */
	@Test
	public void shouldRejectReservedChartPlanningToolName() {
		SqlToolValidator validator = new SqlToolValidator();

		assertThatThrownBy(() -> validator.validateToolName(ToolNames.CHART_PLAN))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Tool 名称为系统保留名称");
	}

}
