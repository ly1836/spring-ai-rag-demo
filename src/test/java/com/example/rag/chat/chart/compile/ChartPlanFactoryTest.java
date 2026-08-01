package com.example.rag.chat.chart.compile;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.rag.chat.chart.model.BusinessToolResult;
import com.example.rag.chat.chart.model.ChartPlan;
import com.example.rag.chat.chart.protocol.ChartSpecCodec;
import com.example.rag.vo.ChartVO;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 后端图表规划生成器测试。
 */
class ChartPlanFactoryTest {

	/**
	 * 验证后端可为全部受支持图表类型生成可编译的字段绑定、转换和安全选项。
	 */
	@Test
	public void shouldGenerateCompilablePlansForAllChartTypes() {
		ChartPlanValidator validator = new ChartPlanValidator();
		ChartPlanFactory factory = new ChartPlanFactory(validator);
		ChartCompiler compiler = new ChartCompiler(validator, new ChartSpecCodec());

		for (ChartVO.ChartType type : ChartVO.ChartType.values()) {
			BusinessToolResult result = resultFor(type);
			List<ChartPlan> candidates = factory.createCandidates(type, "业务数据图表", List.of(result));

			assertThat(candidates).as("图表类型 %s 应生成候选规划", type.getCode()).isNotEmpty();
			assertThatCode(() -> compiler.compile(candidates.get(0), List.of(result)))
				.as("图表类型 %s 的自动规划应通过完整编译", type.getCode())
				.doesNotThrowAnyException();
		}
	}

	/**
	 * 为指定图表类型构造最小有效业务结果。
	 *
	 * @param type 图表类型
	 * @return 业务结果
	 */
	private BusinessToolResult resultFor(ChartVO.ChartType type) {
		List<Map<String, Object>> rows = switch (type) {
			case SUNBURST, TREEMAP -> List.of(
				row("id", "root", "parent_id", null, "name", "根节点", "value", 30),
				row("id", "child", "parent_id", "root", "name", "子节点", "value", 20));
			case BULLET -> List.of(row(
				"category", "计划A", "actual", 80, "target", 100, "range", 120));
			case RADAR, PARALLEL -> List.of(
				row("name", "对象A", "metric_a", 10, "metric_b", 20, "metric_c", 30),
				row("name", "对象B", "metric_a", 15, "metric_b", 25, "metric_c", 35));
			case SCATTER -> List.of(
				row("x_value", 1, "y_value", 2), row("x_value", 3, "y_value", 4));
			case BUBBLE -> List.of(
				row("x_value", 1, "y_value", 2, "size", 10),
				row("x_value", 3, "y_value", 4, "size", 20));
			case HEATMAP -> List.of(
				row("x_label", "一月", "y_label", "产品A", "value", 10),
				row("x_label", "二月", "y_label", "产品A", "value", 20));
			case SANKEY -> List.of(
				row("source", "销售", "target", "回款", "value", 10),
				row("source", "回款", "target", "结算", "value", 8));
			case GANTT -> List.of(
				row("category", "任务A", "start_date", "2026-01-01", "end_date", "2026-01-05"),
				row("category", "任务B", "start_date", "2026-01-03", "end_date", "2026-01-08"));
			case GAUGE, LIQUID_FILL -> List.of(row("name", "完成率", "value", 80));
			case HISTOGRAM -> List.of(
				row("value", 10), row("value", 20), row("value", 30));
			default -> List.of(
				row("name", "产品A", "category", "产品A", "x", "一月", "value", 10),
				row("name", "产品B", "category", "产品B", "x", "二月", "value", 20));
		};
		return new BusinessToolResult(1, "query_business", "database", rows, Instant.now());
	}

	/**
	 * 按键值对构造保持字段顺序的业务数据行。
	 *
	 * @param values 交替排列的字段和值
	 * @return 业务数据行
	 */
	private Map<String, Object> row(Object... values) {
		Map<String, Object> row = new LinkedHashMap<>();
		for (int index = 0; index < values.length; index += 2) {
			row.put(String.valueOf(values[index]), values[index + 1]);
		}
		return row;
	}

	/**
	 * 验证条形图在没有原始数值列时可按分类字段统计真实记录数。
	 */
	@Test
	public void shouldGenerateCategoryCountPlanForBarChart() {
		ChartPlanValidator validator = new ChartPlanValidator();
		ChartPlanFactory factory = new ChartPlanFactory(validator);
		ChartCompiler compiler = new ChartCompiler(validator, new ChartSpecCodec());
		BusinessToolResult result = new BusinessToolResult(1, "query_tickets", "database", List.of(
			row("ticket_no", "T001", "status", "已解决"),
			row("ticket_no", "T002", "status", "已解决"),
			row("ticket_no", "T003", "status", "待处理")), Instant.now());

		List<ChartPlan> candidates = factory.createCandidates(
			ChartVO.ChartType.BAR, "工单处理状态", List.of(result));
		ChartVO.ChartSpec chart = compiler.compile(candidates.get(0), List.of(result));

		assertThat(chart.encoding().get("category")).containsExactly("status");
		assertThat(chart.encoding().get("value")).containsExactly("ticket_no");
		assertThat(chart.dataset().rows()).containsExactly(
			row("status", "已解决", "ticket_no", 2L),
			row("status", "待处理", "ticket_no", 1L));
	}

	/**
	 * 验证水位图自动区分比例和百分比范围，并拒绝超出可信百分比范围的数值。
	 */
	@Test
	public void shouldNormalizeLiquidFillRatioAndPercentageWithTrustedRanges() {
		ChartPlanValidator validator = new ChartPlanValidator();
		ChartPlanFactory factory = new ChartPlanFactory(validator);
		ChartCompiler compiler = new ChartCompiler(validator, new ChartSpecCodec());
		BusinessToolResult percentageResult = new BusinessToolResult(1, "query_percentage", "database",
			List.of(row("name", "完成率", "value", 80)), Instant.now());
		BusinessToolResult ratioResult = new BusinessToolResult(2, "query_ratio", "database",
			List.of(row("name", "完成率", "value", 0.8)), Instant.now());
		BusinessToolResult outOfRangeResult = new BusinessToolResult(3, "query_out_of_range", "database",
			List.of(row("name", "完成率", "value", 120)), Instant.now());

		ChartVO.ChartSpec percentageChart = compiler.compile(factory.createCandidates(
			ChartVO.ChartType.LIQUID_FILL, "完成率", List.of(percentageResult)).get(0), List.of(percentageResult));
		ChartVO.ChartSpec ratioChart = compiler.compile(factory.createCandidates(
			ChartVO.ChartType.LIQUID_FILL, "完成率", List.of(ratioResult)).get(0), List.of(ratioResult));

		assertThat(percentageChart.options().max()).isEqualTo(100.0);
		assertThat(percentageChart.dataset().rows().get(0).get("normalized")).isEqualTo(0.8);
		assertThat(ratioChart.options().max()).isEqualTo(1.0);
		assertThat(ratioChart.dataset().rows().get(0).get("normalized")).isEqualTo(0.8);
		assertThatThrownBy(() -> compiler.compile(factory.createCandidates(
			ChartVO.ChartType.LIQUID_FILL, "完成率", List.of(outOfRangeResult)).get(0),
			List.of(outOfRangeResult)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("水位图数值超出归一范围");
	}

	/**
	 * 验证标题明确要求按产品对比时自动绑定系列，并按月份和产品联合保留数据。
	 */
	@Test
	public void shouldBindSeriesAndGroupByAxisAndSeries() {
		ChartPlanValidator validator = new ChartPlanValidator();
		ChartPlanFactory factory = new ChartPlanFactory(validator);
		ChartCompiler compiler = new ChartCompiler(validator, new ChartSpecCodec());
		BusinessToolResult result = new BusinessToolResult(1, "query_sales_trend", "database", List.of(
			row("month", "一月", "product_name", "产品A", "amount", 10),
			row("month", "一月", "product_name", "产品A", "amount", 5),
			row("month", "一月", "product_name", "产品B", "amount", 20),
			row("month", "二月", "product_name", "产品A", "amount", 15),
			row("month", "二月", "product_name", "产品B", "amount", 25)), Instant.now());

		ChartPlan plan = factory.createCandidates(
			ChartVO.ChartType.LINE, "各产品月度销售金额趋势", List.of(result)).get(0);
		ChartVO.ChartSpec chart = compiler.compile(plan, List.of(result));

		assertThat(plan.transform().groupBy()).containsExactly("month", "product_name");
		assertThat(chart.encoding().get("series")).containsExactly("product_name");
		assertThat(chart.dataset().rows()).hasSize(4);
	}

	/**
	 * 验证甘特图自动绑定 0～1 进度字段，并拒绝静默换算百分比字段。
	 */
	@Test
	public void shouldBindOnlyUnitIntervalGanttProgress() {
		ChartPlanValidator validator = new ChartPlanValidator();
		ChartPlanFactory factory = new ChartPlanFactory(validator);
		BusinessToolResult ratioResult = new BusinessToolResult(1, "query_schedule", "database", List.of(
			row("task", "任务A", "start_date", "2026-01-01", "end_date", "2026-01-05", "progress", 0.5),
			row("task", "任务B", "start_date", "2026-01-03", "end_date", "2026-01-08", "progress", 0.75)),
			Instant.now());
		BusinessToolResult percentageResult = new BusinessToolResult(2, "query_schedule_percent", "database", List.of(
			row("task", "任务A", "start_date", "2026-01-01", "end_date", "2026-01-05", "progress", 50),
			row("task", "任务B", "start_date", "2026-01-03", "end_date", "2026-01-08", "progress", 75)),
			Instant.now());
		BusinessToolResult numericCategoryResult = new BusinessToolResult(3, "query_numeric_schedule", "database", List.of(
			row("task_id", 101, "start_date", "2026-01-01", "end_date", "2026-01-05", "progress", 0.5),
			row("task_id", 102, "start_date", "2026-01-03", "end_date", "2026-01-08", "progress", 0.75)),
			Instant.now());

		ChartPlan ratioPlan = factory.createCandidates(
			ChartVO.ChartType.GANTT, "任务执行进度", List.of(ratioResult)).get(0);
		ChartPlan percentagePlan = factory.createCandidates(
			ChartVO.ChartType.GANTT, "任务执行进度", List.of(percentageResult)).get(0);
		ChartPlan numericCategoryPlan = factory.createCandidates(
			ChartVO.ChartType.GANTT, "任务执行进度", List.of(numericCategoryResult)).get(0);

		assertThat(ratioPlan.bindings()).extracting(ChartPlan.Binding::channel)
			.contains("progress");
		assertThat(percentagePlan.bindings()).extracting(ChartPlan.Binding::channel)
			.doesNotContain("progress");
		assertThat(numericCategoryPlan.bindings()).filteredOn(binding -> "category".equals(binding.channel()))
			.extracting(ChartPlan.Binding::field)
			.containsExactly("task_id");
	}

	/**
	 * 验证数值型业务标识不会被自动绑定为趋势图指标。
	 */
	@Test
	public void shouldExcludeNumericIdentifierFromMetricBindings() {
		ChartPlanFactory factory = new ChartPlanFactory(new ChartPlanValidator());
		BusinessToolResult result = new BusinessToolResult(1, "query_sales_orders", "database", List.of(
			row("order_id", 1001, "order_date", "2026-03-01", "amount", 500),
			row("order_id", 1002, "order_date", "2026-03-02", "amount", 700)), Instant.now());

		ChartPlan plan = factory.createCandidates(
			ChartVO.ChartType.LINE, "Sales order trend", List.of(result)).get(0);

		assertThat(plan.bindings()).filteredOn(binding -> "x".equals(binding.channel()))
			.extracting(ChartPlan.Binding::field)
			.containsExactly("order_date");
		assertThat(plan.bindings()).filteredOn(binding -> "y".equals(binding.channel()))
			.extracting(ChartPlan.Binding::field)
			.containsExactly("amount");
	}

	/**
	 * 验证标题明确指向地区等自定义分类字段时自动绑定系列并联合分组。
	 */
	@Test
	public void shouldBindCustomSeriesDimensionReferencedByTitle() {
		ChartPlanValidator validator = new ChartPlanValidator();
		ChartPlanFactory factory = new ChartPlanFactory(validator);
		ChartCompiler compiler = new ChartCompiler(validator, new ChartSpecCodec());
		BusinessToolResult result = new BusinessToolResult(1, "query_regional_sales", "database", List.of(
			row("month", "一月", "region", "华东", "amount", 10),
			row("month", "一月", "region", "华东", "amount", 5),
			row("month", "一月", "region", "华南", "amount", 20),
			row("month", "二月", "region", "华东", "amount", 15),
			row("month", "二月", "region", "华南", "amount", 25)), Instant.now());

		ChartPlan plan = factory.createCandidates(
			ChartVO.ChartType.LINE, "各地区月度销售金额趋势", List.of(result)).get(0);
		ChartVO.ChartSpec chart = compiler.compile(plan, List.of(result));

		assertThat(plan.transform().groupBy()).containsExactly("month", "region");
		assertThat(chart.encoding().get("series")).containsExactly("region");
	}

}
