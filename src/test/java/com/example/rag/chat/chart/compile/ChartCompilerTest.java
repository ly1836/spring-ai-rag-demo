package com.example.rag.chat.chart.compile;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.alibaba.fastjson2.JSON;
import com.example.rag.chat.chart.model.BusinessToolResult;
import com.example.rag.chat.chart.model.ChartPlan;
import com.example.rag.chat.chart.protocol.ChartSpecCodec;
import com.example.rag.vo.ChartVO;
import com.example.rag.vo.ChartVO.ChartType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 图表规划校验与后端编译器测试。
 */
class ChartCompilerTest {

	private final ChartSpecCodec codec = new ChartSpecCodec();
	private final ChartCompiler compiler = new ChartCompiler(new ChartPlanValidator(), codec);

	/**
	 * 验证全部 23 种图表 fixture 均可生成有效通用协议。
	 */
	@Test
	public void shouldCompileAllSupportedChartFixtures() {
		List<BusinessToolResult> results = List.of(toolResult(fixtureRows()));

		for (ChartType type : ChartType.values()) {
			ChartVO.ChartSpec chart = compiler.compile(planFor(type), results);

			assertThat(chart.type()).isEqualTo(type);
			assertThat(chart.dataset().rows()).isNotEmpty();
			assertThat(chart.dataset().rows()).hasSizeLessThanOrEqualTo(ChartSpecCodec.MAX_ROWS);
			assertThat(codec.encode(chart)).contains("\"schemaVersion\":\"1.0\"");
		}
	}

	/**
	 * 验证分组聚合保持 BigDecimal 精度并按数值降序。
	 */
	@Test
	public void shouldAggregateAndSortWithBigDecimalPrecision() {
		List<Map<String, Object>> rows = List.of(
			Map.of("category", "A", "value", new BigDecimal("0.10")),
			Map.of("category", "A", "value", new BigDecimal("0.20")),
			Map.of("category", "B", "value", new BigDecimal("0.25")));
		ChartPlan plan = new ChartPlan(ChartType.BAR, "精度测试", List.of("query_fixture"), null,
			List.of(
				binding("category", "category"),
				new ChartPlan.Binding("value", "value", "数值", "sum", "元")),
			new ChartPlan.Transform("aggregate", List.of("category"), "value", "desc", 50),
			defaultOptions());

		ChartVO.ChartSpec chart = compiler.compile(plan, List.of(toolResult(rows)));

		assertThat(chart.dataset().rows()).hasSize(2);
		assertThat(chart.dataset().rows().get(0).get("value"))
			.isEqualTo(new BigDecimal("0.30"));
	}

	/**
	 * 验证直方图分箱数量始终处于 5 到 20。
	 */
	@Test
	public void shouldApplySturgesHistogramBinsWithinLimits() {
		ChartVO.ChartSpec chart = compiler.compile(planFor(ChartType.HISTOGRAM),
			List.of(toolResult(fixtureRows())));

		assertThat(chart.options().binCount()).isBetween(5, 20);
		assertThat(chart.dataset().rows()).hasSize(chart.options().binCount());
	}

	/**
	 * 验证箱线图会计算五数概括和 1.5 IQR 异常值。
	 */
	@Test
	public void shouldCompileBoxplotStatisticsAndOutliers() {
		List<Map<String, Object>> rows = List.of(
			Map.of("category", "A", "value", 1),
			Map.of("category", "A", "value", 2),
			Map.of("category", "A", "value", 3),
			Map.of("category", "A", "value", 4),
			Map.of("category", "A", "value", 100));

		ChartVO.ChartSpec chart = compiler.compile(planFor(ChartType.BOXPLOT), List.of(toolResult(rows)));

		assertThat(chart.dataset().rows()).singleElement().satisfies(row -> {
			assertThat(row).containsKeys("min", "q1", "median", "q3", "max", "outliers");
			assertThat(row.get("outliers")).isEqualTo(List.of(new BigDecimal("100")));
		});
	}

	/**
	 * 验证瀑布图会计算累计基线和正负增量。
	 */
	@Test
	public void shouldCompileWaterfallBaseline() {
		List<Map<String, Object>> rows = List.of(
			Map.of("category", "收入", "value", 100),
			Map.of("category", "成本", "value", -40));

		ChartVO.ChartSpec chart = compiler.compile(planFor(ChartType.WATERFALL), List.of(toolResult(rows)));

		assertThat(chart.dataset().rows().get(1))
			.containsEntry("base", new BigDecimal("60"))
			.containsEntry("decrease", new BigDecimal("40"));
	}

	/**
	 * 验证层级图会拒绝父子环和缺失父节点。
	 */
	@Test
	public void shouldRejectInvalidHierarchy() {
		List<Map<String, Object>> cyclic = List.of(
			hierarchyRow("A", "B", 10),
			hierarchyRow("B", "A", 20));
		List<Map<String, Object>> missingParent = List.of(hierarchyRow("A", "missing", 10));

		assertThatThrownBy(() -> compiler.compile(planFor(ChartType.SUNBURST), List.of(toolResult(cyclic))))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("层级图存在父子环");
		assertThatThrownBy(() -> compiler.compile(planFor(ChartType.TREEMAP), List.of(toolResult(missingParent))))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("层级图父节点不存在");
	}

	/**
	 * 验证缺失必需通道、字段类型错误和单位冲突会被拒绝。
	 */
	@Test
	public void shouldRejectInvalidBindingsAndUnits() {
		ChartPlan missingChannel = new ChartPlan(ChartType.BAR, "缺少通道", List.of("query_fixture"), null,
			List.of(binding("category", "category")), identityTransform(), defaultOptions());
		ChartPlan wrongType = new ChartPlan(ChartType.SCATTER, "类型错误", List.of("query_fixture"), null,
			List.of(binding("x", "name"), binding("y", "value")), identityTransform(), defaultOptions());
		ChartPlan unitConflict = new ChartPlan(ChartType.BAR, "单位冲突", List.of("query_fixture"), null,
			List.of(
				binding("category", "category"),
				new ChartPlan.Binding("value", "value", "值", "none", "元"),
				new ChartPlan.Binding("series", "value", "值", "none", "美元")),
			identityTransform(), defaultOptions());

		assertThatThrownBy(() -> compiler.compile(missingChannel, List.of(toolResult(fixtureRows()))))
			.hasMessage("图表缺少必需语义通道");
		assertThatThrownBy(() -> compiler.compile(wrongType, List.of(toolResult(fixtureRows()))))
			.hasMessage("图表通道要求数值字段");
		assertThatThrownBy(() -> compiler.compile(unitConflict, List.of(toolResult(fixtureRows()))))
			.hasMessage("同一字段单位冲突");
	}

	/**
	 * 验证数据行和维度数量遵守协议上限。
	 */
	@Test
	public void shouldEnforceRowAndDimensionLimits() {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (int index = 0; index < 80; index++) {
			rows.add(Map.of("category", "C" + index, "value", index));
		}
		List<ChartPlan.Binding> bindings = new ArrayList<>();
		bindings.add(binding("name", "name"));
		for (int index = 0; index < ChartSpecCodec.MAX_DIMENSIONS; index++) {
			bindings.add(new ChartPlan.Binding("parallel", "p" + (index % 3),
				"指标" + index, "none", null));
		}
		ChartPlan tooManyDimensions = new ChartPlan(ChartType.PARALLEL, "维度上限",
			List.of("query_fixture"), null, bindings, identityTransform(), defaultOptions());

		ChartVO.ChartSpec limited = compiler.compile(planFor(ChartType.BAR), List.of(toolResult(rows)));

		assertThat(limited.dataset().rows()).hasSize(ChartSpecCodec.MAX_ROWS);
		assertThatThrownBy(() -> compiler.compile(tooManyDimensions, List.of(toolResult(fixtureRows()))))
			.hasMessage("图表字段绑定数量不合法");
	}

	/**
	 * 根据图表类型构造合法规划 fixture。
	 *
	 * @param type 图表类型
	 * @return 合法规划
	 */
	private ChartPlan planFor(ChartType type) {
		List<ChartPlan.Binding> bindings = switch (type) {
			case PIE, DONUT, FUNNEL, WORD_CLOUD, GAUGE, LIQUID_FILL -> List.of(
				binding("name", "name"), binding("value", "value"));
			case SUNBURST, TREEMAP -> List.of(
				binding("id", "id"), binding("parentId", "parentId"),
				binding("name", "name"), binding("value", "value"));
			case BAR, WATERFALL -> List.of(
				binding("category", "category"), binding("value", "value"));
			case BULLET -> List.of(
				binding("category", "category"), binding("actual", "actual"),
				binding("target", "bulletTarget"), binding("range", "range"));
			case AREA, STEP, LINE -> List.of(
				binding("x", "x"), binding("y", "y"), binding("series", "series"));
			case RADAR -> List.of(
				binding("name", "name"), binding("indicator", "p1"),
				binding("indicator", "p2"), binding("indicator", "p3"));
			case SCATTER -> List.of(binding("x", "x"), binding("y", "y"));
			case BUBBLE -> List.of(
				binding("x", "x"), binding("y", "y"), binding("size", "size"));
			case HISTOGRAM -> List.of(binding("value", "value"));
			case BOXPLOT -> List.of(
				binding("category", "category"), binding("value", "value"));
			case HEATMAP -> List.of(
				binding("x", "x"), binding("y", "y"), binding("value", "value"));
			case SANKEY -> List.of(
				binding("source", "source"), binding("target", "target"), binding("value", "value"));
			case GANTT -> List.of(
				binding("category", "category"), binding("start", "start"),
				binding("end", "end"), binding("progress", "progress"));
			case PARALLEL -> List.of(
				binding("name", "name"), binding("parallel", "p1"),
				binding("parallel", "p2"), binding("parallel", "p3"));
		};
		String transformType = switch (type) {
			case HISTOGRAM -> "histogram";
			case BOXPLOT -> "boxplot";
			case WATERFALL -> "waterfall";
			case SUNBURST, TREEMAP -> "hierarchy";
			default -> "identity";
		};
		// 仪表盘 fixture 只保留单个业务指标，符合单值图表语义。
		int limit = Set.of(ChartType.GAUGE, ChartType.LIQUID_FILL).contains(type) ? 1 : 50;
		return new ChartPlan(type, type.getCode() + "测试", List.of("query_fixture"), null, bindings,
			new ChartPlan.Transform(transformType, List.of(), null, null, limit), defaultOptions());
	}

	/**
	 * 构造覆盖所有图表字段的测试数据。
	 *
	 * @return 测试数据行
	 */
	private List<Map<String, Object>> fixtureRows() {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (int index = 0; index < 6; index++) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", index == 0 ? "root" : "node" + index);
			row.put("parentId", index == 0 ? "" : "root");
			row.put("name", "项目" + index);
			row.put("category", index < 3 ? "A" : "B");
			row.put("value", index + 1);
			row.put("actual", index + 1);
			row.put("bulletTarget", 10);
			row.put("range", 12);
			row.put("x", index + 1);
			row.put("y", (index + 1) * 2);
			row.put("size", index + 2);
			row.put("series", index % 2 == 0 ? "一组" : "二组");
			row.put("source", "节点" + index);
			row.put("target", "节点" + (index + 1));
			row.put("start", "2026-07-" + String.format("%02d", index + 1));
			row.put("end", "2026-07-" + String.format("%02d", index + 2));
			row.put("progress", 0.5);
			row.put("p1", index + 1);
			row.put("p2", index + 2);
			row.put("p3", index + 3);
			rows.add(row);
		}
		return rows;
	}

	/**
	 * 构造字段绑定。
	 *
	 * @param channel 语义通道
	 * @param field   来源字段
	 * @return 字段绑定
	 */
	private ChartPlan.Binding binding(String channel, String field) {
		return new ChartPlan.Binding(channel, field, field + "名称", "none", null);
	}

	/**
	 * 构造默认恒等转换。
	 *
	 * @return 恒等转换
	 */
	private ChartPlan.Transform identityTransform() {
		return new ChartPlan.Transform("identity", List.of(), null, null, 50);
	}

	/**
	 * 构造默认安全展示选项。
	 *
	 * @return 展示选项
	 */
	private ChartVO.ChartOptions defaultOptions() {
		return new ChartVO.ChartOptions("vertical", false, false, "middle",
			null, 0.0, 100.0, null, "desc", true);
	}

	/**
	 * 构造业务 Tool 结果。
	 *
	 * @param rows 数据行
	 * @return Tool 结果
	 */
	private BusinessToolResult toolResult(List<Map<String, Object>> rows) {
		return new BusinessToolResult(1, "query_fixture", "database", rows, Instant.now());
	}

	/**
	 * 构造层级图数据行。
	 *
	 * @param id       节点 ID
	 * @param parentId 父节点 ID
	 * @param value    节点值
	 * @return 数据行
	 */
	private Map<String, Object> hierarchyRow(String id, String parentId, int value) {
		return Map.of("id", id, "parentId", parentId, "name", id, "value", value);
	}

	/**
	 * 验证图表类型不接受编译器不会执行的数据转换。
	 */
	@Test
	public void shouldRejectTransformIncompatibleWithChartType() {
		ChartPlan plan = new ChartPlan(ChartType.BAR, "转换类型错误", List.of("query_fixture"), null,
			List.of(binding("category", "category"), binding("value", "value")),
			new ChartPlan.Transform("histogram", List.of(), null, null, 50), defaultOptions());

		assertThatThrownBy(() -> compiler.compile(plan, List.of(toolResult(fixtureRows()))))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("数据转换与图表类型不匹配");
	}

	/**
	 * 验证水位图数值超出显式上下界时拒绝编译。
	 */
	@Test
	public void shouldRejectLiquidFillValueOutsideConfiguredRange() {
		ChartPlan plan = planFor(ChartType.LIQUID_FILL);
		List<Map<String, Object>> rows = List.of(Map.of("name", "库存水位", "value", 120));

		assertThatThrownBy(() -> compiler.compile(plan, List.of(toolResult(rows))))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("水位图数值超出归一范围");
	}

	/**
	 * 验证常量样本的直方图分箱边界始终递增且不会丢失计数。
	 */
	@Test
	public void shouldCompileIncreasingHistogramBinsForConstantValues() {
		List<Map<String, Object>> rows = List.of(
			Map.of("value", 5),
			Map.of("value", 5),
			Map.of("value", 5));

		ChartVO.ChartSpec chart = compiler.compile(planFor(ChartType.HISTOGRAM), List.of(toolResult(rows)));

		assertThat(chart.dataset().rows()).allSatisfy(row ->
			assertThat((BigDecimal) row.get("binStart"))
				.isLessThan((BigDecimal) row.get("binEnd")));
		assertThat(chart.dataset().rows()).extracting(row -> (Integer) row.get("count"))
			.satisfies(counts -> assertThat(counts.stream().mapToInt(Integer::intValue).sum()).isEqualTo(3));
	}

	/**
	 * 验证未配置范围的仪表盘按默认 0 到 100 拒绝越界业务值。
	 */
	@Test
	public void shouldRejectGaugeValueOutsideDefaultRange() {
		ChartPlan plan = new ChartPlan(ChartType.GAUGE, "默认范围", List.of("query_fixture"), null,
			List.of(binding("name", "name"), binding("value", "value")),
			identityTransform(), null);
		List<Map<String, Object>> rows = List.of(Map.of("name", "完成率", "value", 150));

		assertThatThrownBy(() -> compiler.compile(plan, List.of(toolResult(rows))))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("仪表盘数值超出范围");
	}

	/**
	 * 验证子弹图实际值与目标值的显式单位不一致时拒绝编译。
	 */
	@Test
	public void shouldRejectBulletActualAndTargetUnitConflict() {
		ChartPlan plan = new ChartPlan(ChartType.BULLET, "单位冲突", List.of("query_fixture"), null,
			List.of(
				binding("category", "category"),
				new ChartPlan.Binding("actual", "actual", "实际值", "none", "元"),
				new ChartPlan.Binding("target", "bulletTarget", "目标值", "none", "美元")),
			identityTransform(), null);

		assertThatThrownBy(() -> compiler.compile(plan, List.of(toolResult(fixtureRows()))))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("子弹图数值字段单位冲突");
	}

	/**
	 * 验证聚合声明必须与转换类型和分组字段匹配。
	 */
	@Test
	public void shouldRejectAggregateDeclarationIncompatibleWithTransform() {
		ChartPlan identityWithAggregate = new ChartPlan(
			ChartType.BAR, "恒等转换错误聚合", List.of("query_fixture"), null,
			List.of(
				binding("category", "category"),
				new ChartPlan.Binding("value", "value", "数值", "sum", null)),
			identityTransform(), defaultOptions());
		ChartPlan aggregateWithoutReducer = new ChartPlan(
			ChartType.BAR, "聚合转换缺少聚合方式", List.of("query_fixture"), null,
			List.of(binding("category", "category"), binding("value", "value")),
			new ChartPlan.Transform("aggregate", List.of("category"), null, null, 50),
			defaultOptions());
		List<Map<String, Object>> rows = List.of(
			Map.of("category", "A", "value", 1),
			Map.of("category", "A", "value", 2));

		assertThatThrownBy(() -> compiler.compile(
			identityWithAggregate, List.of(toolResult(rows))))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("非聚合转换不能声明聚合方式");
		assertThatThrownBy(() -> compiler.compile(
			aggregateWithoutReducer, List.of(toolResult(rows))))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("聚合转换的非分组字段必须声明聚合方式");
	}

	/**
	 * 验证桑基图会拒绝两个及以上节点构成的有向环。
	 */
	@Test
	public void shouldRejectCyclicSankeyGraph() {
		List<Map<String, Object>> rows = List.of(
			Map.of("source", "A", "target", "B", "value", 10),
			Map.of("source", "B", "target", "A", "value", 20));

		assertThatThrownBy(() -> compiler.compile(
			planFor(ChartType.SANKEY), List.of(toolResult(rows))))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("桑基图存在循环路径");
	}

	/**
	 * 验证仪表盘必须只包含一个业务指标。
	 */
	@Test
	public void shouldRejectGaugeWithMultipleRows() {
		ChartPlan plan = new ChartPlan(ChartType.GAUGE, "多指标仪表盘", List.of("query_fixture"), null,
			List.of(binding("name", "name"), binding("value", "value")),
			identityTransform(), defaultOptions());
		List<Map<String, Object>> rows = List.of(
			Map.of("name", "指标A", "value", 10),
			Map.of("name", "指标B", "value", 20));

		assertThatThrownBy(() -> compiler.compile(
			plan, List.of(toolResult(rows))))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("仪表盘只能包含一条数据");
	}

	/**
	 * 验证子弹图目标值单位必须与统一展示单位一致。
	 */
	@Test
	public void shouldRejectBulletTargetAndOptionUnitConflict() {
		ChartPlan plan = new ChartPlan(ChartType.BULLET, "展示单位冲突", List.of("query_fixture"), null,
			List.of(
				binding("category", "category"),
				binding("actual", "actual"),
				new ChartPlan.Binding("target", "bulletTarget", "目标值", "none", "美元")),
			identityTransform(),
			new ChartVO.ChartOptions("vertical", false, false, "middle",
				null, 0.0, 100.0, "元", "desc", true));

		assertThatThrownBy(() -> compiler.compile(plan, List.of(toolResult(fixtureRows()))))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("图表展示单位与字段单位冲突");
	}

	/**
	 * 验证排序字段必须在编译结果中可执行，统计图使用各自明确规则。
	 */
	@Test
	public void shouldRejectUnavailableSortFieldAndSortBoxplotByCategory() {
		List<Map<String, Object>> rows = List.of(
			Map.of("category", "B", "value", 2, "priority", 1),
			Map.of("category", "A", "value", 1, "priority", 2));
		ChartPlan unavailableSort = new ChartPlan(
			ChartType.BAR, "不可用排序字段", List.of("query_fixture"), null,
			List.of(binding("category", "category"), binding("value", "value")),
			new ChartPlan.Transform("identity", List.of(), "priority", "asc", 50),
			defaultOptions());
		ChartPlan histogramSort = new ChartPlan(
			ChartType.HISTOGRAM, "直方图排序", List.of("query_fixture"), null,
			List.of(binding("value", "value")),
			new ChartPlan.Transform("histogram", List.of(), "value", "asc", 50),
			defaultOptions());
		ChartPlan boxplotSort = new ChartPlan(
			ChartType.BOXPLOT, "箱线图类别排序", List.of("query_fixture"), null,
			List.of(binding("category", "category"), binding("value", "value")),
			new ChartPlan.Transform("boxplot", List.of(), "category", "asc", 50),
			defaultOptions());

		assertThatThrownBy(() -> compiler.compile(unavailableSort, List.of(toolResult(rows))))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("排序字段未绑定到可排序图表通道");
		assertThatThrownBy(() -> compiler.compile(histogramSort, List.of(toolResult(rows))))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("排序字段未绑定到可排序图表通道");
		assertThat(compiler.compile(boxplotSort, List.of(toolResult(rows))).dataset().rows())
			.extracting(row -> row.get("category"))
			.containsExactly("A", "B");
	}

	/**
	 * 验证雷达图负指标和水位图多指标都会被拒绝。
	 */
	@Test
	public void shouldRejectNegativeRadarValueAndMultipleLiquidFillRows() {
		List<Map<String, Object>> radarRows = List.of(
			Map.of("name", "项目A", "p1", -1, "p2", 2, "p3", 3));
		ChartPlan liquidPlan = new ChartPlan(
			ChartType.LIQUID_FILL, "多指标水位图", List.of("query_fixture"), null,
			List.of(binding("name", "name"), binding("value", "value")),
			identityTransform(), defaultOptions());
		List<Map<String, Object>> liquidRows = List.of(
			Map.of("name", "指标A", "value", 10),
			Map.of("name", "指标B", "value", 20));

		assertThatThrownBy(() -> compiler.compile(
			planFor(ChartType.RADAR), List.of(toolResult(radarRows))))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("图表数值不能为负数");
		assertThatThrownBy(() -> compiler.compile(
			liquidPlan, List.of(toolResult(liquidRows))))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("水位图只能包含一条数据");
	}

	/**
	 * 验证除明确多字段通道外，同一语义通道不能绑定多个字段。
	 */
	@Test
	public void shouldRejectMultipleBindingsForSingleValueChannel() {
		ChartPlan plan = new ChartPlan(
			ChartType.BAR, "重复通道", List.of("query_fixture"), null,
			List.of(
				binding("category", "category"),
				binding("category", "name"),
				binding("value", "value")),
			identityTransform(), defaultOptions());

		assertThatThrownBy(() -> compiler.compile(plan, List.of(toolResult(fixtureRows()))))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("图表语义通道只能绑定一个字段");
	}

	/**
	 * 验证调用顺序选中的 Tool 名称集合必须完整匹配声明来源。
	 */
	@Test
	public void shouldRequireExactToolNamesForSelectedCallIndexes() {
		ChartPlan plan = new ChartPlan(
			ChartType.BAR, "来源不匹配", List.of("query_fixture"), List.of(2),
			List.of(binding("category", "category"), binding("value", "value")),
			identityTransform(), defaultOptions());
		ChartPlan duplicateIndexes = new ChartPlan(
			ChartType.BAR, "重复调用顺序", List.of("query_fixture"), List.of(1, 1),
			List.of(binding("category", "category"), binding("value", "value")),
			identityTransform(), defaultOptions());
		List<BusinessToolResult> results = List.of(
			new BusinessToolResult(1, "query_fixture", "database", fixtureRows(), Instant.now()),
			new BusinessToolResult(2, "query_other", "database", fixtureRows(), Instant.now()));

		assertThatThrownBy(() -> compiler.compile(plan, results))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("来源Tool名称与调用顺序不匹配");
		assertThatThrownBy(() -> compiler.compile(duplicateIndexes, results))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("来源Tool调用顺序不合法");
	}

	/**
	 * 验证普通数值通道拒绝空值，而 count 可以统计字符串业务标识。
	 */
	@Test
	public void shouldRejectNullNumericValueAndCountStringIdentifiers() {
		Map<String, Object> nullValueRow = new LinkedHashMap<>();
		nullValueRow.put("category", "A");
		nullValueRow.put("value", null);
		ChartPlan countPlan = new ChartPlan(
			ChartType.BAR, "订单数量", List.of("query_fixture"), null,
			List.of(
				binding("category", "category"),
				new ChartPlan.Binding("value", "orderNo", "订单数", "count", null)),
			new ChartPlan.Transform("aggregate", List.of("category"), null, null, 50),
			defaultOptions());
		List<Map<String, Object>> orderRows = List.of(
			Map.of("category", "A", "orderNo", "SO-001"),
			Map.of("category", "A", "orderNo", "SO-002"));

		assertThatThrownBy(() -> compiler.compile(
			planFor(ChartType.BAR), List.of(toolResult(List.of(nullValueRow)))))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("图表数值字段不能为空");
		assertThat(compiler.compile(countPlan, List.of(toolResult(orderRows))).dataset().rows())
			.singleElement()
			.satisfies(row -> assertThat(row.get("orderNo")).isEqualTo(2L));
	}

	/**
	 * 验证分组字段只允许用于聚合且必须引用已绑定字段。
	 */
	@Test
	public void shouldRestrictGroupByToAggregateBoundFields() {
		List<Map<String, Object>> rows = List.of(
			Map.of("category", "A", "value", 1, "hidden", "甲"),
			Map.of("category", "A", "value", 2, "hidden", "乙"));
		ChartPlan identityGroupBy = new ChartPlan(
			ChartType.BAR, "恒等分组", List.of("query_fixture"), null,
			List.of(binding("category", "category"), binding("value", "value")),
			new ChartPlan.Transform("identity", List.of("category"), null, null, 50),
			defaultOptions());
		ChartPlan unboundGroupBy = new ChartPlan(
			ChartType.BAR, "未绑定分组", List.of("query_fixture"), null,
			List.of(
				binding("category", "category"),
				new ChartPlan.Binding("value", "value", "数值", "sum", null)),
			new ChartPlan.Transform("aggregate", List.of("category", "hidden"), null, null, 50),
			defaultOptions());

		assertThatThrownBy(() -> compiler.compile(identityGroupBy, List.of(toolResult(rows))))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("非聚合转换不能声明分组字段");
		assertThatThrownBy(() -> compiler.compile(unboundGroupBy, List.of(toolResult(rows))))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("分组字段必须引用已绑定字段");
	}

	/**
	 * 验证 MySQL DATETIME 可用于甘特图并统一输出 ISO-8601 时间。
	 */
	@Test
	public void shouldNormalizeMysqlDateTimeForGantt() {
		List<Map<String, Object>> rows = List.of(Map.of(
			"category", "排产任务",
			"start", "2026-07-31 08:00:00.123",
			"end", "2026-07-31 10:30:00",
			"progress", 0.5));

		ChartVO.ChartSpec chart = compiler.compile(
			planFor(ChartType.GANTT), List.of(toolResult(rows)));

		assertThat(chart.dataset().rows()).singleElement().satisfies(row -> {
			assertThat(row.get("start")).isEqualTo("2026-07-31T08:00:00.123");
			assertThat(row.get("end")).isEqualTo("2026-07-31T10:30");
		});
	}

	/**
	 * 验证热力图允许两个字符串类别坐标，并与前端共享同一份协议 fixture。
	 *
	 * @throws IOException 共享 fixture 无法读取时抛出
	 */
	@Test
	public void shouldCompileSharedCategoricalHeatmapFixture() throws IOException {
		SharedHeatmapFixture fixture = loadSharedHeatmapFixture();
		ChartPlan plan = new ChartPlan(
			ChartType.HEATMAP, "区域月度销售热力图", List.of("query_heatmap"), null,
			List.of(
				new ChartPlan.Binding("x", "month", "月份", "none", null),
				new ChartPlan.Binding("y", "region", "区域", "none", null),
				new ChartPlan.Binding("value", "sales", "销售额", "none", "元")),
			identityTransform(), fixture.chartSpec().options());
		BusinessToolResult result = new BusinessToolResult(
			1, "query_heatmap", "database", fixture.sourceRows(), Instant.now());

		ChartVO.ChartSpec actual = compiler.compile(plan, List.of(result));

		assertThat(actual.type()).isEqualTo(fixture.chartSpec().type());
		assertThat(actual.dataset()).isEqualTo(fixture.chartSpec().dataset());
		assertThat(actual.encoding()).isEqualTo(fixture.chartSpec().encoding());
		assertThat(actual.options()).isEqualTo(fixture.chartSpec().options());
		assertThat(actual.source()).isEqualTo(fixture.chartSpec().source());
		assertThat(codec.decode(codec.encode(actual)).dataset()).isEqualTo(fixture.chartSpec().dataset());
	}

	/**
	 * 验证没有可信来源语义和单位元数据时拒绝合并多个业务 Tool 结果。
	 */
	@Test
	public void shouldRejectMultipleToolSources() {
		ChartPlan plan = new ChartPlan(
			ChartType.BAR, "多来源金额", List.of("query_fixture", "query_other"), null,
			List.of(binding("category", "category"), binding("value", "value")),
			identityTransform(), defaultOptions());
		List<BusinessToolResult> results = List.of(
			new BusinessToolResult(1, "query_fixture", "database", fixtureRows(), Instant.now()),
			new BusinessToolResult(2, "query_other", "database", fixtureRows(), Instant.now()));

		assertThatThrownBy(() -> compiler.compile(plan, results))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("图表规划只能选择一个来源Tool");
	}

	/**
	 * 验证编译器派生字段不会覆盖同名业务字段，并与前端共享碰撞协议 fixture。
	 *
	 * @throws IOException 共享 fixture 无法读取时抛出
	 */
	@Test
	public void shouldAvoidGeneratedFieldNameCollisions() throws IOException {
		SharedHeatmapFixture fixture = loadSharedHeatmapFixture();
		ChartPlan bubblePlan = new ChartPlan(
			ChartType.BUBBLE, "气泡字段碰撞", List.of("query_fixture"), null,
			List.of(binding("x", "x"), binding("y", "y"), binding("size", "size"),
				binding("series", "visualSize")), identityTransform(), defaultOptions());
		ChartPlan liquidPlan = new ChartPlan(
			ChartType.LIQUID_FILL, "水位字段碰撞", List.of("query_fixture"), null,
			List.of(binding("name", "name"), binding("value", "normalized")),
			new ChartPlan.Transform("identity", List.of(), null, null, 1), defaultOptions());
		ChartPlan radarPlan = new ChartPlan(
			ChartType.RADAR, "雷达字段碰撞", List.of("query_fixture"), null,
			List.of(binding("name", "name"), binding("indicator", "p1"),
				binding("indicator", "p1Max"), binding("indicator", "p3")),
			identityTransform(), defaultOptions());

		ChartVO.ChartSpec bubble = compiler.compile(bubblePlan, List.of(toolResult(List.of(
			Map.of("x", 1, "y", 2, "size", 3, "visualSize", "业务系列")))));
		ChartVO.ChartSpec liquid = compiler.compile(liquidPlan, List.of(toolResult(List.of(
			Map.of("name", "库存水位", "normalized", 50)))));
		ChartVO.ChartSpec radar = compiler.compile(radarPlan, List.of(toolResult(List.of(
			Map.of("name", "项目A", "p1", 1, "p1Max", 2, "p3", 3)))));

		String bubbleVisualField = bubble.encoding().get("visualSize").get(0);
		String liquidNormalizedField = liquid.encoding().get("normalized").get(0);
		assertThat(bubbleVisualField).isNotEqualTo("visualSize");
		assertThat(bubble.dataset().rows().get(0))
			.containsEntry("visualSize", "业务系列")
			.containsKey(bubbleVisualField);
		assertThat(liquidNormalizedField).isNotEqualTo("normalized");
		assertThat(liquid.dataset().rows().get(0))
			.containsEntry("normalized", 50)
			.containsEntry(liquidNormalizedField, 0.5d);
		assertThat(radar.encoding().get("indicatorMax").get(0)).isNotEqualTo("p1Max");
		assertThat(radar.dataset().rows().get(0)).containsEntry("p1Max", 2);
		// 共享协议确保后端生成键和值与前端实际消费的数据保持一致。
		ChartVO.ChartSpec expectedBubble = fixture.collisionSpecs().get(0);
		ChartVO.ChartSpec expectedLiquid = fixture.collisionSpecs().get(1);
		assertThat(bubble.dataset().dimensions()).isEqualTo(expectedBubble.dataset().dimensions());
		assertThat(bubble.encoding()).isEqualTo(expectedBubble.encoding());
		assertThat(((Number) bubble.dataset().rows().get(0).get(bubbleVisualField)).doubleValue())
			.isEqualTo(((Number) expectedBubble.dataset().rows().get(0).get(bubbleVisualField)).doubleValue());
		assertThat(liquid.dataset().dimensions()).isEqualTo(expectedLiquid.dataset().dimensions());
		assertThat(liquid.encoding()).isEqualTo(expectedLiquid.encoding());
		assertThat(((Number) liquid.dataset().rows().get(0).get(liquidNormalizedField)).doubleValue())
			.isEqualTo(((Number) expectedLiquid.dataset().rows().get(0).get(liquidNormalizedField)).doubleValue());
	}

	/**
	 * 读取后端编译与前端适配共用的热力图 fixture。
	 *
	 * @return 共享热力图 fixture
	 * @throws IOException fixture 无法读取时抛出
	 */
	private SharedHeatmapFixture loadSharedHeatmapFixture() throws IOException {
		try (InputStream input = ChartCompilerTest.class.getResourceAsStream("/chart-pipeline-fixture.json")) {
			assertThat(input).as("共享图表 fixture 必须存在").isNotNull();
			return JSON.parseObject(new String(input.readAllBytes(), StandardCharsets.UTF_8),
				SharedHeatmapFixture.class);
		}
	}

	/**
	 * 后端编译与前端适配共用的图表协议 fixture。
	 *
	 * @param sourceRows     业务 Tool 来源行
	 * @param chartSpec      期望热力图协议
	 * @param collisionSpecs 派生字段碰撞协议
	 */
	private record SharedHeatmapFixture(List<Map<String, Object>> sourceRows,
			ChartVO.ChartSpec chartSpec, List<ChartVO.ChartSpec> collisionSpecs) {
	}

	/**
	 * 验证普通字符串类目不会因部分值形似日期而被误判为混合类型。
	 */
	@Test
	public void shouldKeepDateLikeCategoryValuesAsStrings() {
		ChartPlan plan = new ChartPlan(
			ChartType.BAR, "日期形文本类目", List.of("query_fixture"), null,
			List.of(binding("category", "category"), binding("value", "value")),
			identityTransform(), defaultOptions());
		List<Map<String, Object>> rows = List.of(
			Map.of("category", "2026-07-31", "value", 1),
			Map.of("category", "其他", "value", 2));

		ChartVO.ChartSpec chart = compiler.compile(plan, List.of(toolResult(rows)));

		assertThat(chart.dataset().dimensions())
			.filteredOn(dimension -> "category".equals(dimension.key()))
			.singleElement()
			.extracting(ChartVO.Dimension::dataType)
			.isEqualTo("string");
		assertThat(chart.dataset().rows())
			.extracting(row -> row.get("category"))
			.containsExactly("2026-07-31", "其他");
	}

}
