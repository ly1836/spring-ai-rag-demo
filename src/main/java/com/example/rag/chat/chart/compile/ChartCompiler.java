package com.example.rag.chat.chart.compile;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.example.rag.chat.chart.model.BusinessToolResult;
import com.example.rag.chat.chart.model.ChartPlan;
import com.example.rag.chat.chart.protocol.ChartSpecCodec;
import com.example.rag.vo.ChartVO;
import com.example.rag.vo.ChartVO.ChartType;

import org.springframework.stereotype.Component;

/**
 * 从真实业务 Tool 结果编译通用图表协议。
 */
@Component
public class ChartCompiler {

	/** ERP MySQL DATETIME 字符串解析格式，兼容零到九位小数秒。 */
	private static final DateTimeFormatter MYSQL_DATETIME_FORMATTER = new DateTimeFormatterBuilder()
		.appendPattern("yyyy-MM-dd HH:mm:ss")
		.optionalStart()
		.appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
		.optionalEnd()
		.toFormatter();

	/** 图表规划校验器。 */
	private final ChartPlanValidator validator;

	/** 图表协议编解码器。 */
	private final ChartSpecCodec codec;

	/**
	 * 创建图表编译器。
	 *
	 * @param validator 图表规划校验器
	 * @param codec     图表协议编解码器
	 */
	public ChartCompiler(ChartPlanValidator validator, ChartSpecCodec codec) {
		this.validator = validator;
		this.codec = codec;
	}

	/**
	 * 校验规划并从真实 Tool 结果生成图表协议。
	 *
	 * @param plan    图表规划
	 * @param results 本轮业务 Tool 结果
	 * @return 编译后的图表协议
	 */
	public ChartVO.ChartSpec compile(ChartPlan plan, List<BusinessToolResult> results) {
		validator.validate(plan, results);
		List<BusinessToolResult> selected = validator.selectResults(plan, results);
		List<Map<String, Object>> sourceRows = selected.stream()
			.flatMap(result -> result.rows().stream())
			.toList();
		CompiledData data = switch (plan.type()) {
			case HISTOGRAM -> compileHistogram(plan, sourceRows);
			case BOXPLOT -> compileBoxplot(plan, sourceRows);
			case WATERFALL -> compileWaterfall(plan, sourceRows);
			case SUNBURST, TREEMAP -> compileHierarchy(plan, sourceRows);
			case SANKEY -> compileSankey(plan, sourceRows);
			case GANTT -> compileGantt(plan, sourceRows);
			case BUBBLE -> compileBubble(plan, sourceRows);
			case LIQUID_FILL -> compileLiquidFill(plan, sourceRows);
			default -> compileGeneric(plan, sourceRows);
		};
		validateSemanticValues(plan, data.rows());
		ChartVO.ChartSpec chart = new ChartVO.ChartSpec(
			ChartVO.SCHEMA_VERSION, UUID.randomUUID().toString(), plan.type(), plan.title(), null,
			new ChartVO.Dataset(data.dimensions(), data.rows()), data.encoding(), data.options(),
			new ChartVO.ChartSource(selected.stream()
				.map(BusinessToolResult::toolName)
				.distinct()
				.toList()));
		codec.validate(chart);
		codec.encode(chart);
		return chart;
	}

	/**
	 * 编译通用投影、聚合、排序和截断。
	 *
	 * @param plan       图表规划
	 * @param sourceRows 来源数据行
	 * @return 编译数据
	 */
	private CompiledData compileGeneric(ChartPlan plan, List<Map<String, Object>> sourceRows) {
		List<Map<String, Object>> rows;
		String transformType = plan.transform() == null || plan.transform().type() == null
			? "identity" : plan.transform().type();
		if (Set.of(ChartType.PIE, ChartType.DONUT, ChartType.FUNNEL, ChartType.WORD_CLOUD)
				.contains(plan.type())
				&& "identity".equals(transformType)) {
			rows = aggregateCategoricalValues(plan, sourceRows);
		}
		else if ("aggregate".equals(transformType)) {
			rows = aggregateRows(plan, sourceRows);
		}
		else {
			rows = sourceRows.stream().map(row -> projectRow(plan.bindings(), row)).toList();
		}
		rows = sortAndLimit(plan, rows);
		if (plan.type() == ChartType.RADAR) {
			rows = addRadarMaximums(plan, rows);
		}
		return standardData(plan, rows, plan.options());
	}

	/**
	 * 编译直方图并使用 Sturges 规则确定默认分箱数。
	 *
	 * @param plan       图表规划
	 * @param sourceRows 来源数据行
	 * @return 直方图数据
	 */
	private CompiledData compileHistogram(ChartPlan plan, List<Map<String, Object>> sourceRows) {
		String valueField = fieldFor(plan, "value");
		List<BigDecimal> values = numericValues(sourceRows, valueField);
		if (values.isEmpty()) {
			throw new IllegalArgumentException("直方图没有有效数值");
		}
		int defaultBins = (int) Math.ceil(1 + Math.log(values.size()) / Math.log(2));
		int binCount = plan.options() != null && plan.options().binCount() != null
			? plan.options().binCount() : Math.max(5, Math.min(20, defaultBins));
		BigDecimal min = values.stream().min(BigDecimal::compareTo).orElseThrow();
		BigDecimal max = values.stream().max(BigDecimal::compareTo).orElseThrow();
		boolean constantValues = max.compareTo(min) == 0;
		// 常量样本扩为对称单位区间，确保所有分箱的起止边界保持递增。
		BigDecimal rangeMin = constantValues ? min.subtract(new BigDecimal("0.5")) : min;
		BigDecimal rangeMax = constantValues ? max.add(new BigDecimal("0.5")) : max;
		BigDecimal width = rangeMax.subtract(rangeMin)
			.divide(BigDecimal.valueOf(binCount), MathContext.DECIMAL128);
		int[] counts = new int[binCount];
		for (BigDecimal value : values) {
			int index = width.compareTo(BigDecimal.ZERO) == 0 ? 0
				: value.subtract(rangeMin).divide(width, 0, RoundingMode.FLOOR).intValue();
			counts[Math.max(0, Math.min(binCount - 1, index))]++;
		}
		List<Map<String, Object>> rows = new ArrayList<>();
		for (int index = 0; index < binCount; index++) {
			BigDecimal start = rangeMin.add(width.multiply(BigDecimal.valueOf(index)));
			BigDecimal end = index == binCount - 1 ? rangeMax
				: rangeMin.add(width.multiply(BigDecimal.valueOf(index + 1)));
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("binStart", start);
			row.put("binEnd", end);
			row.put("binLabel", start.stripTrailingZeros().toPlainString()
				+ " - " + end.stripTrailingZeros().toPlainString());
			row.put("count", counts[index]);
			rows.add(row);
		}
		ChartVO.ChartOptions options = copyOptions(plan.options(), binCount,
			rangeMin.doubleValue(), rangeMax.doubleValue());
		return new CompiledData(
			List.of(
				new ChartVO.Dimension("binStart", "区间起点", "number", bindingFor(plan, "value").unit()),
				new ChartVO.Dimension("binEnd", "区间终点", "number", bindingFor(plan, "value").unit()),
				new ChartVO.Dimension("binLabel", "区间", "string", null),
				new ChartVO.Dimension("count", "数量", "number", null)),
			copyRows(rows), Map.of("category", List.of("binLabel"), "value", List.of("count")), options);
	}

	/**
	 * 编译箱线图的五数概括和 1.5 IQR 异常值。
	 *
	 * @param plan       图表规划
	 * @param sourceRows 来源数据行
	 * @return 箱线图数据
	 */
	private CompiledData compileBoxplot(ChartPlan plan, List<Map<String, Object>> sourceRows) {
		String categoryField = fieldFor(plan, "category");
		String valueField = fieldFor(plan, "value");
		Map<String, List<BigDecimal>> groups = new LinkedHashMap<>();
		for (Map<String, Object> row : sourceRows) {
			String category = String.valueOf(row.get(categoryField));
			groups.computeIfAbsent(category, key -> new ArrayList<>()).add(toDecimal(row.get(valueField)));
		}
		List<Map<String, Object>> rows = new ArrayList<>();
		for (Map.Entry<String, List<BigDecimal>> entry : groups.entrySet()) {
			List<BigDecimal> values = entry.getValue().stream().sorted().toList();
			BigDecimal q1 = quantile(values, 0.25);
			BigDecimal median = quantile(values, 0.5);
			BigDecimal q3 = quantile(values, 0.75);
			BigDecimal iqr = q3.subtract(q1);
			BigDecimal lowerFence = q1.subtract(iqr.multiply(BigDecimal.valueOf(1.5)));
			BigDecimal upperFence = q3.add(iqr.multiply(BigDecimal.valueOf(1.5)));
			List<BigDecimal> normal = values.stream()
				.filter(value -> value.compareTo(lowerFence) >= 0 && value.compareTo(upperFence) <= 0)
				.toList();
			List<BigDecimal> outliers = values.stream()
				.filter(value -> value.compareTo(lowerFence) < 0 || value.compareTo(upperFence) > 0)
				.toList();
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("category", entry.getKey());
			row.put("min", normal.isEmpty() ? values.get(0) : normal.get(0));
			row.put("q1", q1);
			row.put("median", median);
			row.put("q3", q3);
			row.put("max", normal.isEmpty() ? values.get(values.size() - 1) : normal.get(normal.size() - 1));
			row.put("outliers", outliers);
			rows.add(row);
		}
		String unit = bindingFor(plan, "value").unit();
		return new CompiledData(
			List.of(
				new ChartVO.Dimension("category", bindingFor(plan, "category").label(), "string", null),
				new ChartVO.Dimension("min", "最小值", "number", unit),
				new ChartVO.Dimension("q1", "下四分位数", "number", unit),
				new ChartVO.Dimension("median", "中位数", "number", unit),
				new ChartVO.Dimension("q3", "上四分位数", "number", unit),
				new ChartVO.Dimension("max", "最大值", "number", unit),
				new ChartVO.Dimension("outliers", "异常值", "string", unit)),
			copyRows(sortAndLimit(plan, rows)),
			Map.of("category", List.of("category"),
				"value", List.of("min", "q1", "median", "q3", "max"),
				"outlier", List.of("outliers")),
			plan.options());
	}

	/**
	 * 编译瀑布图的累计基线、增量和减量。
	 *
	 * @param plan       图表规划
	 * @param sourceRows 来源数据行
	 * @return 瀑布图数据
	 */
	private CompiledData compileWaterfall(ChartPlan plan, List<Map<String, Object>> sourceRows) {
		List<Map<String, Object>> projected = sourceRows.stream()
			.map(row -> projectRow(plan.bindings(), row))
			.toList();
		projected = sortAndLimit(plan, projected);
		String categoryField = fieldFor(plan, "category");
		String valueField = fieldFor(plan, "value");
		BigDecimal cumulative = BigDecimal.ZERO;
		List<Map<String, Object>> rows = new ArrayList<>();
		for (Map<String, Object> source : projected) {
			BigDecimal value = toDecimal(source.get(valueField));
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("category", source.get(categoryField));
			row.put("base", value.signum() >= 0 ? cumulative : cumulative.add(value));
			row.put("increase", value.signum() >= 0 ? value : BigDecimal.ZERO);
			row.put("decrease", value.signum() < 0 ? value.abs() : BigDecimal.ZERO);
			row.put("value", value);
			cumulative = cumulative.add(value);
			rows.add(row);
		}
		String unit = bindingFor(plan, "value").unit();
		return new CompiledData(
			List.of(
				new ChartVO.Dimension("category", bindingFor(plan, "category").label(), "string", null),
				new ChartVO.Dimension("base", "累计基线", "number", unit),
				new ChartVO.Dimension("increase", "增加", "number", unit),
				new ChartVO.Dimension("decrease", "减少", "number", unit),
				new ChartVO.Dimension("value", bindingFor(plan, "value").label(), "number", unit)),
			copyRows(rows),
			Map.of("category", List.of("category"), "base", List.of("base"),
				"increase", List.of("increase"), "decrease", List.of("decrease"),
				"value", List.of("value")),
			plan.options());
	}

	/**
	 * 编译旭日图和矩形树图，并校验父节点与层级环。
	 *
	 * @param plan       图表规划
	 * @param sourceRows 来源数据行
	 * @return 层级图数据
	 */
	private CompiledData compileHierarchy(ChartPlan plan, List<Map<String, Object>> sourceRows) {
		String idField = fieldFor(plan, "id");
		String parentField = fieldFor(plan, "parentId");
		List<Map<String, Object>> rows = sourceRows.stream()
			.map(row -> projectRow(plan.bindings(), row))
			.map(row -> {
				// 根节点的空父级统一转换为空字符串，保持层级语义并满足对外协议非空字段约束。
				Map<String, Object> normalized = new LinkedHashMap<>(row);
				if (normalized.get(parentField) == null) {
					normalized.put(parentField, "");
				}
				return normalized;
			})
			.toList();
		rows = sortAndLimit(plan, rows);
		Set<String> ids = rows.stream().map(row -> String.valueOf(row.get(idField)))
			.collect(Collectors.toCollection(LinkedHashSet::new));
		if (ids.size() != rows.size() || ids.stream().anyMatch(id -> id.isBlank() || "null".equals(id))) {
			throw new IllegalArgumentException("层级图节点标识不能为空或重复");
		}
		Map<String, String> parents = new LinkedHashMap<>();
		for (Map<String, Object> row : rows) {
			String id = String.valueOf(row.get(idField));
			Object parentValue = row.get(parentField);
			String parent = parentValue == null ? "" : String.valueOf(parentValue);
			if (!parent.isBlank() && !ids.contains(parent)) {
				throw new IllegalArgumentException("层级图父节点不存在");
			}
			parents.put(id, parent);
		}
		for (String id : ids) {
			Set<String> visited = new HashSet<>();
			String current = id;
			while (current != null && !current.isBlank()) {
				if (!visited.add(current)) {
					throw new IllegalArgumentException("层级图存在父子环");
				}
				current = parents.get(current);
			}
		}
		return standardData(plan, rows, plan.options());
	}

	/**
	 * 编译桑基图并校验边权重。
	 *
	 * @param plan       图表规划
	 * @param sourceRows 来源数据行
	 * @return 桑基图数据
	 */
	private CompiledData compileSankey(ChartPlan plan, List<Map<String, Object>> sourceRows) {
		List<Map<String, Object>> rows = sourceRows.stream()
			.map(row -> projectRow(plan.bindings(), row))
			.toList();
		rows = sortAndLimit(plan, rows);
		String sourceField = fieldFor(plan, "source");
		String targetField = fieldFor(plan, "target");
		String valueField = fieldFor(plan, "value");
		for (Map<String, Object> row : rows) {
			String source = row.get(sourceField) == null ? "" : String.valueOf(row.get(sourceField));
			String target = row.get(targetField) == null ? "" : String.valueOf(row.get(targetField));
			if (source.isBlank() || target.isBlank()) {
				throw new IllegalArgumentException("桑基图节点不能为空");
			}
			if (source.equals(target)) {
				throw new IllegalArgumentException("桑基图不允许自循环边");
			}
			if (toDecimal(row.get(valueField)).signum() < 0) {
				throw new IllegalArgumentException("桑基图权重不能为负数");
			}
		}
		validateSankeyAcyclic(rows, sourceField, targetField);
		return standardData(plan, rows, plan.options());
	}

	/**
	 * 编译甘特图并校验起止时间。
	 *
	 * @param plan       图表规划
	 * @param sourceRows 来源数据行
	 * @return 甘特图数据
	 */
	private CompiledData compileGantt(ChartPlan plan, List<Map<String, Object>> sourceRows) {
		List<Map<String, Object>> rows = sourceRows.stream()
			.map(row -> projectRow(plan.bindings(), row))
			.toList();
		rows = sortAndLimit(plan, rows);
		String startField = fieldFor(plan, "start");
		String endField = fieldFor(plan, "end");
		List<Map<String, Object>> normalizedRows = new ArrayList<>();
		for (Map<String, Object> row : rows) {
			if (toEpochMillis(row.get(startField)) > toEpochMillis(row.get(endField))) {
				throw new IllegalArgumentException("甘特图开始时间不能晚于结束时间");
			}
			// 对外统一输出浏览器可稳定解析的 ISO-8601 时间字符串。
			Map<String, Object> normalizedRow = new LinkedHashMap<>(row);
			normalizedRow.put(startField, normalizeDateValue(row.get(startField)));
			normalizedRow.put(endField, normalizeDateValue(row.get(endField)));
			normalizedRows.add(normalizedRow);
		}
		return standardData(plan, copyRows(normalizedRows), plan.options());
	}

	/**
	 * 编译气泡图并生成 10 到 60 的安全视觉尺寸。
	 *
	 * @param plan       图表规划
	 * @param sourceRows 来源数据行
	 * @return 气泡图数据
	 */
	private CompiledData compileBubble(ChartPlan plan, List<Map<String, Object>> sourceRows) {
		List<Map<String, Object>> projected = sourceRows.stream()
			.map(row -> projectRow(plan.bindings(), row))
			.toList();
		projected = sortAndLimit(plan, projected);
		String sizeField = fieldFor(plan, "size");
		List<BigDecimal> sizes = numericValues(projected, sizeField);
		BigDecimal min = sizes.stream().min(BigDecimal::compareTo).orElseThrow();
		BigDecimal max = sizes.stream().max(BigDecimal::compareTo).orElseThrow();
		String visualSizeField = generatedField(plan, "visualSize", "__chart_visualSize");
		List<Map<String, Object>> rows = new ArrayList<>();
		for (Map<String, Object> source : projected) {
			BigDecimal size = toDecimal(source.get(sizeField));
			if (size.signum() < 0) {
				throw new IllegalArgumentException("气泡大小不能为负数");
			}
			double ratio = max.compareTo(min) == 0 ? 0.5
				: size.subtract(min).divide(max.subtract(min), MathContext.DECIMAL64).doubleValue();
			Map<String, Object> row = new LinkedHashMap<>(source);
			row.put(visualSizeField, 10 + ratio * 50);
			rows.add(row);
		}
		CompiledData standard = standardData(plan, rows, plan.options());
		List<ChartVO.Dimension> dimensions = new ArrayList<>(standard.dimensions());
		dimensions.add(new ChartVO.Dimension(visualSizeField, "显示尺寸", "number", null));
		Map<String, List<String>> encoding = new LinkedHashMap<>(standard.encoding());
		encoding.put("visualSize", List.of(visualSizeField));
		return new CompiledData(List.copyOf(dimensions), standard.rows(),
			Map.copyOf(encoding), standard.options());
	}

	/**
	 * 编译水位图并按安全上下界归一到 0 到 1。
	 *
	 * @param plan       图表规划
	 * @param sourceRows 来源数据行
	 * @return 水位图数据
	 */
	private CompiledData compileLiquidFill(ChartPlan plan, List<Map<String, Object>> sourceRows) {
		List<Map<String, Object>> projected = sourceRows.stream()
			.map(row -> projectRow(plan.bindings(), row))
			.toList();
		projected = sortAndLimit(plan, projected);
		String valueField = fieldFor(plan, "value");
		List<BigDecimal> values = numericValues(projected, valueField);
		double min = plan.options() != null && plan.options().min() != null
			? plan.options().min() : 0.0;
		double max = plan.options() != null && plan.options().max() != null
			? plan.options().max() : values.stream().mapToDouble(BigDecimal::doubleValue).max().orElse(1.0);
		if (plan.options() == null || plan.options().max() == null) {
			max = Math.max(1.0, max);
		}
		if (max <= min) {
			throw new IllegalArgumentException("水位图归一范围不合法");
		}
		String normalizedField = generatedField(plan, "normalized", "__chart_normalized");
		List<Map<String, Object>> rows = new ArrayList<>();
		for (Map<String, Object> source : projected) {
			double sourceValue = toDecimal(source.get(valueField)).doubleValue();
			// 水位图必须拒绝超出业务上下界的数据，不能通过截断掩盖异常值。
			if (sourceValue < min || sourceValue > max) {
				throw new IllegalArgumentException("水位图数值超出归一范围");
			}
			double normalized = (sourceValue - min) / (max - min);
			Map<String, Object> row = new LinkedHashMap<>(source);
			row.put(normalizedField, normalized);
			rows.add(row);
		}
		CompiledData standard = standardData(plan, rows, copyOptions(plan.options(), null, min, max));
		List<ChartVO.Dimension> dimensions = new ArrayList<>(standard.dimensions());
		dimensions.add(new ChartVO.Dimension(normalizedField, "归一值", "number", null));
		Map<String, List<String>> encoding = new LinkedHashMap<>(standard.encoding());
		encoding.put("normalized", List.of(normalizedField));
		return new CompiledData(List.copyOf(dimensions), standard.rows(),
			Map.copyOf(encoding), standard.options());
	}

	/**
	 * 对需额外范围约束的图表类型执行语义数值校验。
	 *
	 * @param plan 图表规划
	 * @param rows 编译后数据行
	 */
	private void validateSemanticValues(ChartPlan plan, List<Map<String, Object>> rows) {
		Set<ChartType> nonNegativeTypes = Set.of(
			ChartType.PIE, ChartType.DONUT, ChartType.FUNNEL,
			ChartType.WORD_CLOUD, ChartType.GAUGE, ChartType.BULLET, ChartType.RADAR);
		if (nonNegativeTypes.contains(plan.type())) {
			for (String channel : List.of("value", "actual", "target", "range", "indicator")) {
				for (ChartPlan.Binding binding : bindingsFor(plan, channel)) {
					for (Map<String, Object> row : rows) {
						Object value = row.get(binding.field());
						if (value != null && toDecimal(value).signum() < 0) {
							throw new IllegalArgumentException("图表数值不能为负数");
						}
					}
				}
			}
		}
		if (plan.type() == ChartType.LIQUID_FILL && rows.size() != 1) {
			// 水位图仅表达一个业务指标，避免多行被前端误解为同一水球的多层波浪。
			throw new IllegalArgumentException("水位图只能包含一条数据");
		}
		if (plan.type() == ChartType.GAUGE) {
			// 仪表盘仅表达单个业务指标，拒绝前端无法完整展示的多行数据。
			if (rows.size() != 1) {
				throw new IllegalArgumentException("仪表盘只能包含一条数据");
			}
			// 与前端适配器保持一致，规划未传范围时按 0 到 100 校验业务值。
			double min = plan.options() != null && plan.options().min() != null
				? plan.options().min() : 0.0;
			double max = plan.options() != null && plan.options().max() != null
				? plan.options().max() : 100.0;
			if (min >= max) {
				throw new IllegalArgumentException("仪表盘最小值必须小于最大值");
			}
			String valueField = fieldFor(plan, "value");
			for (Map<String, Object> row : rows) {
				double value = toDecimal(row.get(valueField)).doubleValue();
				if (value < min || value > max) {
					throw new IllegalArgumentException("仪表盘数值超出范围");
				}
			}
		}
		if (plan.type() == ChartType.BULLET) {
			String actualField = fieldFor(plan, "actual");
			String targetField = fieldFor(plan, "target");
			List<ChartPlan.Binding> ranges = bindingsFor(plan, "range");
			if (!ranges.isEmpty()) {
				String rangeField = ranges.get(0).field();
				for (Map<String, Object> row : rows) {
					BigDecimal range = toDecimal(row.get(rangeField));
					if (toDecimal(row.get(actualField)).compareTo(range) > 0
							|| toDecimal(row.get(targetField)).compareTo(range) > 0) {
						throw new IllegalArgumentException("子弹图实际值或目标值超过范围");
					}
				}
			}
		}
		if (plan.type() == ChartType.WORD_CLOUD) {
			String nameField = fieldFor(plan, "name");
			for (Map<String, Object> row : rows) {
				String name = row.get(nameField) == null ? "" : String.valueOf(row.get(nameField));
				if (name.isBlank() || name.length() > 80) {
					throw new IllegalArgumentException("词云文本长度不合法");
				}
			}
		}
		if (plan.type() == ChartType.GANTT && !bindingsFor(plan, "progress").isEmpty()) {
			String progressField = fieldFor(plan, "progress");
			for (Map<String, Object> row : rows) {
				double progress = toDecimal(row.get(progressField)).doubleValue();
				if (progress < 0.0 || progress > 1.0) {
					throw new IllegalArgumentException("甘特图进度必须位于0到1");
				}
			}
		}
	}

	/**
	 * 按名称合并饼图、环形图、漏斗图和词云图的重复类别。
	 *
	 * @param plan       图表规划
	 * @param sourceRows 来源数据行
	 * @return 类别聚合行
	 */
	private List<Map<String, Object>> aggregateCategoricalValues(ChartPlan plan,
			List<Map<String, Object>> sourceRows) {
		String nameField = fieldFor(plan, "name");
		String valueField = fieldFor(plan, "value");
		Map<Object, BigDecimal> totals = new LinkedHashMap<>();
		for (Map<String, Object> row : sourceRows) {
			totals.merge(row.get(nameField), toDecimal(row.get(valueField)), BigDecimal::add);
		}
		return totals.entrySet().stream()
			.map(entry -> {
				Map<String, Object> row = new LinkedHashMap<>();
				row.put(nameField, entry.getKey());
				row.put(valueField, entry.getValue());
				return row;
			})
			.toList();
	}

	/**
	 * 按规划绑定对单行数据执行投影。
	 *
	 * @param bindings 字段绑定
	 * @param source   来源行
	 * @return 投影行
	 */
	private Map<String, Object> projectRow(List<ChartPlan.Binding> bindings, Map<String, Object> source) {
		Map<String, Object> target = new LinkedHashMap<>();
		for (ChartPlan.Binding binding : bindings) {
			target.put(binding.field(), source.get(binding.field()));
		}
		return target;
	}

	/**
	 * 按分组字段和聚合声明生成统计行。
	 *
	 * @param plan       图表规划
	 * @param sourceRows 来源数据行
	 * @return 聚合数据行
	 */
	private List<Map<String, Object>> aggregateRows(ChartPlan plan, List<Map<String, Object>> sourceRows) {
		List<String> groupBy = plan.transform() != null && plan.transform().groupBy() != null
			? plan.transform().groupBy() : List.of();
		Map<List<Object>, List<Map<String, Object>>> groups = sourceRows.stream()
			.collect(Collectors.groupingBy(
				row -> groupBy.stream().map(row::get).toList(),
				LinkedHashMap::new, Collectors.toList()));
		List<Map<String, Object>> rows = new ArrayList<>();
		for (Map.Entry<List<Object>, List<Map<String, Object>>> entry : groups.entrySet()) {
			Map<String, Object> row = new LinkedHashMap<>();
			for (int index = 0; index < groupBy.size(); index++) {
				row.put(groupBy.get(index), entry.getKey().get(index));
			}
			for (ChartPlan.Binding binding : plan.bindings()) {
				if (row.containsKey(binding.field())) {
					continue;
				}
				String aggregate = binding.aggregate() == null || binding.aggregate().isBlank()
					? "none" : binding.aggregate();
				row.put(binding.field(), aggregateValue(entry.getValue(), binding.field(), aggregate));
			}
			rows.add(row);
		}
		// 聚合后再次按绑定字段投影，避免分组参数把未声明字段带入前端协议。
		return rows.stream().map(row -> projectRow(plan.bindings(), row)).toList();
	}

	/**
	 * 计算单字段聚合值并保持 BigDecimal 精度。
	 *
	 * @param rows      分组数据行
	 * @param field     聚合字段
	 * @param aggregate 聚合方式
	 * @return 聚合结果
	 */
	private Object aggregateValue(List<Map<String, Object>> rows, String field, String aggregate) {
		if ("none".equals(aggregate)) {
			return rows.get(0).get(field);
		}
		if ("count".equals(aggregate)) {
			return rows.stream().filter(row -> row.get(field) != null).count();
		}
		List<BigDecimal> values = numericValues(rows, field);
		if (values.isEmpty()) {
			return null;
		}
		return switch (aggregate) {
			case "sum" -> values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
			case "avg" -> values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
				.divide(BigDecimal.valueOf(values.size()), MathContext.DECIMAL128);
			case "min" -> values.stream().min(BigDecimal::compareTo).orElseThrow();
			case "max" -> values.stream().max(BigDecimal::compareTo).orElseThrow();
			default -> throw new IllegalArgumentException("聚合方式不受支持");
		};
	}

	/**
	 * 按规划排序并截断数据行。
	 *
	 * @param plan 图表规划
	 * @param rows 数据行
	 * @return 排序截断后的数据行
	 */
	private List<Map<String, Object>> sortAndLimit(ChartPlan plan, List<Map<String, Object>> rows) {
		List<Map<String, Object>> sorted = new ArrayList<>(rows);
		String sortBy = plan.transform() == null ? null : plan.transform().sortBy();
		String direction = plan.transform() == null ? null : plan.transform().sortDirection();
		if (sortBy != null) {
			// 箱线图输出使用固定 category 键，排序时映射回来源类别绑定。
			String compiledSortBy = plan.type() == ChartType.BOXPLOT
				&& sortBy.equals(fieldFor(plan, "category")) ? "category" : sortBy;
			Comparator<Map<String, Object>> comparator = Comparator.comparing(
				row -> row.get(compiledSortBy), this::compareValues);
			if ("desc".equals(direction)) {
				comparator = comparator.reversed();
			}
			sorted.sort(comparator);
		}
		int limit = plan.transform() != null && plan.transform().limit() != null
			? plan.transform().limit() : ChartSpecCodec.MAX_ROWS;
		return copyRows(sorted.stream().limit(limit).toList());
	}

	/**
	 * 为雷达图添加每个指标的安全上界。
	 *
	 * @param plan 图表规划
	 * @param rows 数据行
	 * @return 含上界字段的数据行
	 */
	private List<Map<String, Object>> addRadarMaximums(ChartPlan plan, List<Map<String, Object>> rows) {
		Map<String, String> maximumFields = radarMaximumFields(plan);
		Map<String, BigDecimal> maximums = new LinkedHashMap<>();
		for (ChartPlan.Binding binding : bindingsFor(plan, "indicator")) {
			BigDecimal maximum = numericValues(rows, binding.field()).stream()
				.max(BigDecimal::compareTo)
				.orElse(BigDecimal.ONE);
			maximums.put(maximumFields.get(binding.field()),
				maximum.signum() == 0 ? BigDecimal.ONE : maximum.multiply(BigDecimal.valueOf(1.1)));
		}
		List<Map<String, Object>> result = new ArrayList<>();
		for (Map<String, Object> source : rows) {
			Map<String, Object> row = new LinkedHashMap<>(source);
			row.putAll(maximums);
			result.add(row);
		}
		return copyRows(result);
	}

	/**
	 * 根据规划构建通用维度、编码和数据行。
	 *
	 * @param plan    图表规划
	 * @param rows    编译后数据行
	 * @param options 展示选项
	 * @return 通用编译数据
	 */
	private CompiledData standardData(ChartPlan plan, List<Map<String, Object>> rows,
			ChartVO.ChartOptions options) {
		Map<String, ChartVO.Dimension> dimensions = new LinkedHashMap<>();
		Map<String, List<String>> encoding = new LinkedHashMap<>();
		for (ChartPlan.Binding binding : plan.bindings()) {
			dimensions.putIfAbsent(binding.field(), new ChartVO.Dimension(
				binding.field(), binding.label(),
				inferDataType(rows, binding.field(), binding.channel()), binding.unit()));
			encoding.computeIfAbsent(binding.channel(), key -> new ArrayList<>()).add(binding.field());
		}
		if (plan.type() == ChartType.RADAR) {
			Map<String, String> maximumFields = radarMaximumFields(plan);
			for (ChartPlan.Binding binding : bindingsFor(plan, "indicator")) {
				String key = maximumFields.get(binding.field());
				dimensions.put(key, new ChartVO.Dimension(key, binding.label() + "上限", "number", binding.unit()));
				encoding.computeIfAbsent("indicatorMax", channel -> new ArrayList<>()).add(key);
			}
		}
		Map<String, List<String>> immutableEncoding = encoding.entrySet().stream()
			.collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
				entry -> List.copyOf(entry.getValue())));
		return new CompiledData(List.copyOf(dimensions.values()), copyRows(rows), immutableEncoding, options);
	}

	/**
	 * 从指定字段提取数值列表。
	 *
	 * @param rows  数据行
	 * @param field 字段名
	 * @return 数值列表
	 */
	private List<BigDecimal> numericValues(List<Map<String, Object>> rows, String field) {
		return rows.stream()
			.map(row -> row.get(field))
			.filter(value -> value != null)
			.map(this::toDecimal)
			.toList();
	}

	/**
	 * 将 Number 转为保持精度的 BigDecimal。
	 *
	 * @param value 数值
	 * @return BigDecimal
	 */
	private BigDecimal toDecimal(Object value) {
		if (value instanceof BigDecimal decimal) {
			return decimal;
		}
		if (value instanceof Number number) {
			return new BigDecimal(number.toString());
		}
		throw new IllegalArgumentException("字段不是有效数值");
	}

	/**
	 * 计算有序数列的线性插值分位数。
	 *
	 * @param sortedValues 有序数值
	 * @param percentile   分位比例
	 * @return 分位数
	 */
	private BigDecimal quantile(List<BigDecimal> sortedValues, double percentile) {
		if (sortedValues.isEmpty()) {
			throw new IllegalArgumentException("箱线图没有有效数值");
		}
		double position = (sortedValues.size() - 1) * percentile;
		int lower = (int) Math.floor(position);
		int upper = (int) Math.ceil(position);
		if (lower == upper) {
			return sortedValues.get(lower);
		}
		BigDecimal ratio = BigDecimal.valueOf(position - lower);
		return sortedValues.get(lower).multiply(BigDecimal.ONE.subtract(ratio))
			.add(sortedValues.get(upper).multiply(ratio));
	}

	/**
	 * 获取指定语义通道的首个来源字段。
	 *
	 * @param plan    图表规划
	 * @param channel 语义通道
	 * @return 来源字段
	 */
	private String fieldFor(ChartPlan plan, String channel) {
		return bindingFor(plan, channel).field();
	}

	/**
	 * 获取指定语义通道的首个绑定。
	 *
	 * @param plan    图表规划
	 * @param channel 语义通道
	 * @return 字段绑定
	 */
	private ChartPlan.Binding bindingFor(ChartPlan plan, String channel) {
		return bindingsFor(plan, channel).stream()
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("图表缺少语义通道"));
	}

	/**
	 * 获取指定语义通道的所有绑定。
	 *
	 * @param plan    图表规划
	 * @param channel 语义通道
	 * @return 字段绑定列表
	 */
	private List<ChartPlan.Binding> bindingsFor(ChartPlan plan, String channel) {
		return plan.bindings().stream()
			.filter(binding -> channel.equals(binding.channel()))
			.toList();
	}

	/**
	 * 推断编译字段的数据类型。
	 *
	 * @param rows    数据行
	 * @param field   字段名
	 * @param channel 语义通道
	 * @return 协议数据类型
	 */
	private String inferDataType(List<Map<String, Object>> rows, String field, String channel) {
		Object value = rows.stream().map(row -> row.get(field))
			.filter(java.util.Objects::nonNull)
			.findFirst()
			.orElse(null);
		if (value instanceof Number) {
			return "number";
		}
		if (value instanceof Boolean) {
			return "boolean";
		}
		if (value instanceof LocalDate) {
			return "date";
		}
		if (value instanceof LocalDateTime || value instanceof OffsetDateTime) {
			return "datetime";
		}
		// 日期形字符串只在甘特图时间通道声明为日期，其他通道保持原始文本类型。
		if (value instanceof String text && ("start".equals(channel) || "end".equals(channel))
				&& validator.isDateValue(text)) {
			return text.contains("T") ? "datetime" : "date";
		}
		return "string";
	}

	/**
	 * 比较排序字段，数值优先按 BigDecimal 比较。
	 *
	 * @param left  左值
	 * @param right 右值
	 * @return 比较结果
	 */
	private int compareValues(Object left, Object right) {
		if (left == right) {
			return 0;
		}
		if (left == null) {
			return -1;
		}
		if (right == null) {
			return 1;
		}
		if (left instanceof Number && right instanceof Number) {
			return toDecimal(left).compareTo(toDecimal(right));
		}
		return String.valueOf(left).compareTo(String.valueOf(right));
	}

	/**
	 * 将 ISO 日期或时间转换为毫秒时间戳。
	 *
	 * @param value 日期值
	 * @return UTC 毫秒时间戳
	 */
	private long toEpochMillis(Object value) {
		if (value instanceof OffsetDateTime dateTime) {
			return dateTime.toInstant().toEpochMilli();
		}
		if (value instanceof LocalDateTime dateTime) {
			return dateTime.toInstant(ZoneOffset.UTC).toEpochMilli();
		}
		if (value instanceof LocalDate date) {
			return date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
		}
		String text = String.valueOf(value);
		if (text.contains("T")) {
			try {
				return OffsetDateTime.parse(text).toInstant().toEpochMilli();
			}
			catch (Exception ex) {
				return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli();
			}
		}
		if (text.contains(" ")) {
			return LocalDateTime.parse(text, MYSQL_DATETIME_FORMATTER)
				.toInstant(ZoneOffset.UTC).toEpochMilli();
		}
		return LocalDate.parse(text).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
	}

	/**
	 * 复制展示选项并覆盖统计范围。
	 *
	 * @param source   原展示选项
	 * @param binCount 分箱数
	 * @param min      最小值
	 * @param max      最大值
	 * @return 新展示选项
	 */
	private ChartVO.ChartOptions copyOptions(ChartVO.ChartOptions source, Integer binCount,
			Double min, Double max) {
		return new ChartVO.ChartOptions(
			source == null ? null : source.orientation(),
			source == null ? null : source.stacked(),
			source == null ? null : source.smooth(),
			source == null ? null : source.step(),
			binCount,
			min,
			max,
			source == null ? null : source.unit(),
			source == null ? null : source.sort(),
			source == null ? null : source.showLabel());
	}

	/**
	 * 创建允许 null 值的不可变数据行副本。
	 *
	 * @param rows 数据行
	 * @return 不可变数据行
	 */
	private List<Map<String, Object>> copyRows(List<Map<String, Object>> rows) {
		return rows.stream()
			.map(row -> java.util.Collections.unmodifiableMap(new LinkedHashMap<>(row)))
			.toList();
	}

	/**
	 * 校验桑基图有向边不形成间接循环。
	 *
	 * @param rows        桑基图数据行
	 * @param sourceField 来源节点字段
	 * @param targetField 目标节点字段
	 */
	private void validateSankeyAcyclic(List<Map<String, Object>> rows,
			String sourceField, String targetField) {
		Map<String, Set<String>> outgoing = new LinkedHashMap<>();
		Map<String, Integer> indegrees = new LinkedHashMap<>();
		for (Map<String, Object> row : rows) {
			String source = String.valueOf(row.get(sourceField));
			String target = String.valueOf(row.get(targetField));
			indegrees.putIfAbsent(source, 0);
			indegrees.putIfAbsent(target, 0);
			// 重复边只计算一次入度，避免把合法的并行权重误判为环。
			if (outgoing.computeIfAbsent(source, key -> new LinkedHashSet<>()).add(target)) {
				indegrees.merge(target, 1, Integer::sum);
			}
		}
		ArrayDeque<String> roots = new ArrayDeque<>();
		indegrees.forEach((node, indegree) -> {
			if (indegree == 0) {
				roots.add(node);
			}
		});
		int visited = 0;
		while (!roots.isEmpty()) {
			String node = roots.removeFirst();
			visited++;
			for (String target : outgoing.getOrDefault(node, Set.of())) {
				int remaining = indegrees.merge(target, -1, Integer::sum);
				if (remaining == 0) {
					roots.addLast(target);
				}
			}
		}
		if (visited != indegrees.size()) {
			throw new IllegalArgumentException("桑基图存在循环路径");
		}
	}

	/**
	 * 将日期值规范为前端可稳定解析的 ISO-8601 字符串。
	 *
	 * @param value 日期值
	 * @return ISO-8601 日期或时间字符串
	 */
	private String normalizeDateValue(Object value) {
		if (value instanceof OffsetDateTime dateTime) {
			return dateTime.toString();
		}
		if (value instanceof LocalDateTime dateTime) {
			return dateTime.toString();
		}
		if (value instanceof LocalDate date) {
			return date.toString();
		}
		String text = String.valueOf(value);
		if (text.contains(" ")) {
			return LocalDateTime.parse(text, MYSQL_DATETIME_FORMATTER).toString();
		}
		return text;
	}

	/**
	 * 编译器内部统一数据结构。
	 *
	 * @param dimensions 维度
	 * @param rows       数据行
	 * @param encoding   语义编码
	 * @param options    展示选项
	 */
	private record CompiledData(List<ChartVO.Dimension> dimensions,
			List<Map<String, Object>> rows, Map<String, List<String>> encoding,
			ChartVO.ChartOptions options) {
	}

	/**
	 * 为单个编译器派生字段分配不与业务绑定碰撞的字段键。
	 *
	 * @param plan      图表规划
	 * @param preferred 兼容既有协议的首选字段键
	 * @param fallback  首选键冲突时使用的内部字段键
	 * @return 唯一派生字段键
	 */
	private String generatedField(ChartPlan plan, String preferred, String fallback) {
		Set<String> usedFields = plan.bindings().stream()
			.map(ChartPlan.Binding::field)
			.collect(Collectors.toCollection(LinkedHashSet::new));
		return reserveGeneratedField(usedFields, preferred, fallback);
	}

	/**
	 * 为每个雷达指标分配稳定且不覆盖业务字段的上界键。
	 *
	 * @param plan 图表规划
	 * @return 指标字段到上界字段的映射
	 */
	private Map<String, String> radarMaximumFields(ChartPlan plan) {
		Set<String> usedFields = plan.bindings().stream()
			.map(ChartPlan.Binding::field)
			.collect(Collectors.toCollection(LinkedHashSet::new));
		Map<String, String> maximumFields = new LinkedHashMap<>();
		int index = 1;
		for (ChartPlan.Binding binding : bindingsFor(plan, "indicator")) {
			String maximumField = reserveGeneratedField(
				usedFields, binding.field() + "Max", "__chart_indicatorMax" + index);
			maximumFields.put(binding.field(), maximumField);
			index++;
		}
		return maximumFields;
	}

	/**
	 * 在已使用字段集合中预留一个长度合法的唯一派生字段键。
	 *
	 * @param usedFields 已使用字段键
	 * @param preferred 首选字段键
	 * @param fallback  冲突时的内部字段键
	 * @return 已预留的唯一字段键
	 */
	private String reserveGeneratedField(Set<String> usedFields, String preferred, String fallback) {
		if (preferred.length() <= 120 && usedFields.add(preferred)) {
			return preferred;
		}
		String candidate = fallback;
		int suffix = 2;
		while (!usedFields.add(candidate)) {
			String suffixText = "_" + suffix++;
			candidate = fallback.substring(0, Math.min(fallback.length(), 120 - suffixText.length()))
				+ suffixText;
		}
		return candidate;
	}

}
