package com.example.rag.chat.chart.compile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.rag.chat.chart.model.BusinessToolResult;
import com.example.rag.chat.chart.model.ChartPlan;
import com.example.rag.chat.chart.protocol.ChartOptionsValidator;
import com.example.rag.chat.chart.protocol.ChartSpecCodec;
import com.example.rag.vo.ChartVO;
import com.example.rag.vo.ChartVO.ChartType;

import org.springframework.stereotype.Component;

/**
 * 图表规划白名单校验器。
 */
@Component
public class ChartPlanValidator {

	/** 各图表类型的必需语义通道。 */
	private static final Map<ChartType, Set<String>> REQUIRED_CHANNELS = Map.ofEntries(
		Map.entry(ChartType.PIE, Set.of("name", "value")),
		Map.entry(ChartType.DONUT, Set.of("name", "value")),
		Map.entry(ChartType.SUNBURST, Set.of("id", "parentId", "name", "value")),
		Map.entry(ChartType.BAR, Set.of("category", "value")),
		Map.entry(ChartType.WATERFALL, Set.of("category", "value")),
		Map.entry(ChartType.BULLET, Set.of("category", "actual", "target")),
		Map.entry(ChartType.AREA, Set.of("x", "y")),
		Map.entry(ChartType.STEP, Set.of("x", "y")),
		Map.entry(ChartType.RADAR, Set.of("name", "indicator")),
		Map.entry(ChartType.SCATTER, Set.of("x", "y")),
		Map.entry(ChartType.BUBBLE, Set.of("x", "y", "size")),
		Map.entry(ChartType.HISTOGRAM, Set.of("value")),
		Map.entry(ChartType.BOXPLOT, Set.of("category", "value")),
		Map.entry(ChartType.HEATMAP, Set.of("x", "y", "value")),
		Map.entry(ChartType.SANKEY, Set.of("source", "target", "value")),
		Map.entry(ChartType.TREEMAP, Set.of("id", "parentId", "name", "value")),
		Map.entry(ChartType.GANTT, Set.of("category", "start", "end")),
		Map.entry(ChartType.FUNNEL, Set.of("name", "value")),
		Map.entry(ChartType.WORD_CLOUD, Set.of("name", "value")),
		Map.entry(ChartType.GAUGE, Set.of("name", "value")),
		Map.entry(ChartType.LIQUID_FILL, Set.of("name", "value")),
		Map.entry(ChartType.PARALLEL, Set.of("name", "parallel")),
		Map.entry(ChartType.LINE, Set.of("x", "y")));

	/** 各图表类型允许的可选通道。 */
	private static final Map<ChartType, Set<String>> OPTIONAL_CHANNELS = Map.ofEntries(
		Map.entry(ChartType.BAR, Set.of("series")),
		Map.entry(ChartType.BULLET, Set.of("range")),
		Map.entry(ChartType.AREA, Set.of("series")),
		Map.entry(ChartType.STEP, Set.of("series")),
		Map.entry(ChartType.SCATTER, Set.of("series")),
		Map.entry(ChartType.BUBBLE, Set.of("series")),
		Map.entry(ChartType.GANTT, Set.of("progress")),
		Map.entry(ChartType.LINE, Set.of("series")));

	/** 必须绑定数值字段的语义通道。 */
	private static final Set<String> NUMERIC_CHANNELS = Set.of(
		"value", "actual", "range", "size", "indicator", "parallel", "progress");

	/** 必须使用数值纵轴的图表类型。 */
	private static final Set<ChartType> NUMERIC_Y_TYPES = Set.of(
		ChartType.AREA, ChartType.STEP, ChartType.LINE, ChartType.SCATTER, ChartType.BUBBLE);

	/** 允许的聚合方式。 */
	private static final Set<String> AGGREGATES = Set.of("none", "sum", "avg", "min", "max", "count");

	/** 各图表类型允许的数据转换，避免编译器静默忽略不匹配的转换。 */
	private static final Map<ChartType, Set<String>> ALLOWED_TRANSFORMS = Map.ofEntries(
		Map.entry(ChartType.PIE, Set.of("identity", "aggregate")),
		Map.entry(ChartType.DONUT, Set.of("identity", "aggregate")),
		Map.entry(ChartType.SUNBURST, Set.of("identity", "hierarchy")),
		Map.entry(ChartType.BAR, Set.of("identity", "aggregate")),
		Map.entry(ChartType.WATERFALL, Set.of("identity", "waterfall")),
		Map.entry(ChartType.BULLET, Set.of("identity", "aggregate")),
		Map.entry(ChartType.AREA, Set.of("identity", "aggregate")),
		Map.entry(ChartType.STEP, Set.of("identity", "aggregate")),
		Map.entry(ChartType.RADAR, Set.of("identity", "aggregate")),
		Map.entry(ChartType.SCATTER, Set.of("identity", "aggregate")),
		Map.entry(ChartType.BUBBLE, Set.of("identity")),
		Map.entry(ChartType.HISTOGRAM, Set.of("identity", "histogram")),
		Map.entry(ChartType.BOXPLOT, Set.of("identity", "boxplot")),
		Map.entry(ChartType.HEATMAP, Set.of("identity", "aggregate")),
		Map.entry(ChartType.SANKEY, Set.of("identity", "hierarchy")),
		Map.entry(ChartType.TREEMAP, Set.of("identity", "hierarchy")),
		Map.entry(ChartType.GANTT, Set.of("identity")),
		Map.entry(ChartType.FUNNEL, Set.of("identity", "aggregate")),
		Map.entry(ChartType.WORD_CLOUD, Set.of("identity", "aggregate")),
		Map.entry(ChartType.GAUGE, Set.of("identity", "aggregate")),
		Map.entry(ChartType.LIQUID_FILL, Set.of("identity")),
		Map.entry(ChartType.PARALLEL, Set.of("identity", "aggregate")),
		Map.entry(ChartType.LINE, Set.of("identity", "aggregate")));

	/** ERP MySQL DATETIME 字符串解析格式，兼容零到九位小数秒。 */
	private static final DateTimeFormatter MYSQL_DATETIME_FORMATTER = new DateTimeFormatterBuilder()
		.appendPattern("yyyy-MM-dd HH:mm:ss")
		.optionalStart()
		.appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
		.optionalEnd()
		.toFormatter();

	/**
	 * 校验图表规划和选定的业务 Tool 结果。
	 *
	 * @param plan    图表规划
	 * @param results 本轮业务 Tool 结果
	 * @throws IllegalArgumentException 规划不符合白名单时抛出
	 */
	public void validate(ChartPlan plan, List<BusinessToolResult> results) {
		if (plan == null || plan.type() == null) {
			throw new IllegalArgumentException("图表类型不受支持");
		}
		if (!hasSafeText(plan.title(), 120)) {
			throw new IllegalArgumentException("图表标题不合法");
		}
		if (plan.sourceToolNames() == null || plan.sourceToolNames().size() != 1) {
			throw new IllegalArgumentException("图表规划只能选择一个来源Tool");
		}
		if (new HashSet<>(plan.sourceToolNames()).size() != plan.sourceToolNames().size()) {
			throw new IllegalArgumentException("来源Tool不能重复");
		}
		if (plan.sourceCallIndexes() != null
				&& (plan.sourceCallIndexes().size() != 1
					|| new HashSet<>(plan.sourceCallIndexes()).size() != plan.sourceCallIndexes().size())) {
			throw new IllegalArgumentException("来源Tool调用顺序不合法");
		}
		List<BusinessToolResult> selected = selectResults(plan, results);
		validateBindings(plan, selected);
		validateTransform(plan, selected);
		ChartOptionsValidator.validate(plan.options());
		validateOptionUnit(plan);
	}

	/**
	 * 按来源名称或完成顺序选择业务 Tool 结果。
	 *
	 * @param plan    图表规划
	 * @param results 本轮业务 Tool 结果
	 * @return 选定结果
	 */
	public List<BusinessToolResult> selectResults(ChartPlan plan, List<BusinessToolResult> results) {
		if (results == null || results.isEmpty()) {
			throw new IllegalArgumentException("本轮没有可用业务Tool结果");
		}
		List<BusinessToolResult> selected = new ArrayList<>();
		if (plan.sourceCallIndexes() != null && !plan.sourceCallIndexes().isEmpty()) {
			for (Integer sequence : plan.sourceCallIndexes()) {
				BusinessToolResult match = results.stream()
					.filter(result -> sequence != null && result.sequence() == sequence)
					.findFirst()
					.orElseThrow(() -> new IllegalArgumentException("来源Tool调用顺序不存在"));
				if (!plan.sourceToolNames().contains(match.toolName())) {
					throw new IllegalArgumentException("来源Tool名称与调用顺序不匹配");
				}
				selected.add(match);
			}
		}
		else {
			for (String toolName : plan.sourceToolNames()) {
				BusinessToolResult match = results.stream()
					.filter(result -> result.toolName().equals(toolName))
					.reduce((first, second) -> second)
					.orElseThrow(() -> new IllegalArgumentException("来源Tool不在本轮成功结果中"));
				selected.add(match);
			}
		}
		if (selected.isEmpty()) {
			throw new IllegalArgumentException("未选择业务Tool结果");
		}
		// 指定调用顺序时，选中结果必须完整覆盖且不能超出模型声明的来源 Tool。
		Set<String> selectedToolNames = selected.stream()
			.map(BusinessToolResult::toolName)
			.collect(java.util.stream.Collectors.toSet());
		if (!selectedToolNames.equals(new HashSet<>(plan.sourceToolNames()))) {
			throw new IllegalArgumentException("来源Tool名称与调用顺序不匹配");
		}
		return List.copyOf(selected);
	}

	/**
	 * 校验语义通道、来源字段、类型、聚合和单位。
	 *
	 * @param plan     图表规划
	 * @param selected 选定 Tool 结果
	 */
	private void validateBindings(ChartPlan plan, List<BusinessToolResult> selected) {
		if (plan.bindings() == null || plan.bindings().isEmpty()
				|| plan.bindings().size() > ChartSpecCodec.MAX_DIMENSIONS) {
			throw new IllegalArgumentException("图表字段绑定数量不合法");
		}
		Set<String> required = REQUIRED_CHANNELS.get(plan.type());
		Set<String> allowed = new HashSet<>(required);
		allowed.addAll(OPTIONAL_CHANNELS.getOrDefault(plan.type(), Set.of()));
		Map<String, List<ChartPlan.Binding>> byChannel = new LinkedHashMap<>();
		Map<String, String> fieldUnits = new LinkedHashMap<>();
		for (ChartPlan.Binding binding : plan.bindings()) {
			if (binding == null || !allowed.contains(binding.channel())
					|| !hasSafeText(binding.field(), 120) || !hasSafeText(binding.label(), 120)) {
				throw new IllegalArgumentException("图表字段绑定不合法");
			}
			String aggregate = normalize(binding.aggregate(), "none");
			if (!AGGREGATES.contains(aggregate)) {
				throw new IllegalArgumentException("聚合方式不受支持");
			}
			if (binding.unit() != null && !hasSafeText(binding.unit(), 20)) {
				throw new IllegalArgumentException("字段单位不合法");
			}
			String previousUnit = fieldUnits.putIfAbsent(binding.field(), binding.unit());
			if (previousUnit != null && binding.unit() != null && !previousUnit.equals(binding.unit())) {
				throw new IllegalArgumentException("同一字段单位冲突");
			}
			validateField(plan.type(), binding, selected);
			byChannel.computeIfAbsent(binding.channel(), key -> new ArrayList<>()).add(binding);
		}
		if (!byChannel.keySet().containsAll(required)) {
			throw new IllegalArgumentException("图表缺少必需语义通道");
		}
		for (String channel : required) {
			if (byChannel.get(channel).isEmpty()) {
				throw new IllegalArgumentException("图表必需语义通道不能为空");
			}
		}
		// 仅雷达指标和平行坐标维度支持同一语义通道绑定多个字段。
		for (Map.Entry<String, List<ChartPlan.Binding>> entry : byChannel.entrySet()) {
			boolean multipleAllowed = (plan.type() == ChartType.RADAR && "indicator".equals(entry.getKey()))
				|| (plan.type() == ChartType.PARALLEL && "parallel".equals(entry.getKey()));
			if (!multipleAllowed && entry.getValue().size() > 1) {
				throw new IllegalArgumentException("图表语义通道只能绑定一个字段");
			}
		}
		if (plan.type() == ChartType.RADAR && byChannel.get("indicator").size() < 3) {
			throw new IllegalArgumentException("雷达图至少需要三个数值指标");
		}
		if (plan.type() == ChartType.PARALLEL && byChannel.get("parallel").size() < 3) {
			throw new IllegalArgumentException("平行坐标图至少需要三个数值维度");
		}
		validateBulletUnits(plan);
	}

	/**
	 * 校验绑定字段在所有来源数据中存在且类型符合通道要求。
	 *
	 * @param chartType 图表类型
	 * @param binding   字段绑定
	 * @param selected  选定 Tool 结果
	 */
	private void validateField(ChartType chartType, ChartPlan.Binding binding,
			List<BusinessToolResult> selected) {
		boolean numericChannel = isNumericChannel(chartType, binding.channel());
		boolean countAggregate = "count".equals(normalize(binding.aggregate(), "none"));
		String valueFamily = null;
		for (BusinessToolResult result : selected) {
			for (Map<String, Object> row : result.rows()) {
				if (!row.containsKey(binding.field())) {
					throw new IllegalArgumentException("来源字段不存在");
				}
				Object value = row.get(binding.field());
				// count 统计字段可为字符串或空值，其他数值通道必须逐行提供真实数值。
				if (value == null && numericChannel && !countAggregate) {
					throw new IllegalArgumentException("图表数值字段不能为空");
				}
				if (value != null && numericChannel && !countAggregate && !(value instanceof Number)) {
					throw new IllegalArgumentException("图表通道要求数值字段");
				}
				if (value != null && ("start".equals(binding.channel()) || "end".equals(binding.channel()))
						&& !isDateValue(value)) {
					throw new IllegalArgumentException("甘特图时间字段格式不合法");
				}
				if (value != null) {
					String currentFamily = valueFamily(binding.channel(), value);
					if (valueFamily != null && !valueFamily.equals(currentFamily)) {
						throw new IllegalArgumentException("多来源字段数据类型不一致");
					}
					valueFamily = currentFamily;
				}
			}
		}
	}

	/**
	 * 校验转换类型、排序字段、排序方向和数据行限制。
	 *
	 * @param plan     图表规划
	 * @param selected 选定 Tool 结果
	 */
	private void validateTransform(ChartPlan plan, List<BusinessToolResult> selected) {
		ChartPlan.Transform transform = plan.transform();
		if (transform == null) {
			validateAggregateUsage(plan, null);
			return;
		}
		String type = normalize(transform.type(), "identity");
		Set<String> allowedTransforms = ALLOWED_TRANSFORMS.getOrDefault(plan.type(), Set.of());
		if (!allowedTransforms.contains(type)) {
			throw new IllegalArgumentException("数据转换与图表类型不匹配");
		}
		if (transform.limit() != null && (transform.limit() < 1 || transform.limit() > ChartSpecCodec.MAX_ROWS)) {
			throw new IllegalArgumentException("图表数据行限制不合法");
		}
		if (transform.sortDirection() != null
				&& !Set.of("asc", "desc").contains(transform.sortDirection())) {
			throw new IllegalArgumentException("排序方向不受支持");
		}
		if (transform.sortBy() != null) {
			// 排序字段必须在编译后保留；统计图仅开放编译器能够执行的排序字段。
			Set<String> sortableFields = new HashSet<>();
			if (plan.type() != ChartType.HISTOGRAM) {
				for (ChartPlan.Binding binding : plan.bindings()) {
					if (plan.type() != ChartType.BOXPLOT || "category".equals(binding.channel())) {
						sortableFields.add(binding.field());
					}
				}
			}
			if (!sortableFields.contains(transform.sortBy())) {
				throw new IllegalArgumentException("排序字段未绑定到可排序图表通道");
			}
		}
		List<String> groupBy = transform.groupBy() == null ? List.of() : transform.groupBy();
		if (!"aggregate".equals(type) && !groupBy.isEmpty()) {
			throw new IllegalArgumentException("非聚合转换不能声明分组字段");
		}
		if (!groupBy.isEmpty()) {
			Set<String> boundFields = plan.bindings().stream()
				.map(ChartPlan.Binding::field)
				.collect(java.util.stream.Collectors.toSet());
			if (new HashSet<>(groupBy).size() != groupBy.size()) {
				throw new IllegalArgumentException("分组字段不能重复");
			}
			for (String groupField : groupBy) {
				if (!hasSafeText(groupField, 120) || !boundFields.contains(groupField)) {
					throw new IllegalArgumentException("分组字段必须引用已绑定字段");
				}
				if (selected.stream().flatMap(result -> result.rows().stream())
					.anyMatch(row -> !row.containsKey(groupField))) {
					throw new IllegalArgumentException("分组字段不存在");
				}
			}
		}
		validateAggregateUsage(plan, transform);
	}

	/**
	 * 校验统一展示单位与数值字段显式单位不冲突。
	 *
	 * @param plan 图表规划
	 */
	private void validateOptionUnit(ChartPlan plan) {
		if (plan.options() == null || plan.options().unit() == null) {
			return;
		}
		for (ChartPlan.Binding binding : plan.bindings()) {
			if (isNumericChannel(plan.type(), binding.channel()) && binding.unit() != null
					&& !plan.options().unit().equals(binding.unit())) {
				throw new IllegalArgumentException("图表展示单位与字段单位冲突");
			}
		}
	}

	/**
	 * 判断对象是否属于数值类型。
	 *
	 * @param value 待检查对象
	 * @return 是否为数值
	 */
	public boolean isNumeric(Object value) {
		return value instanceof Number || value instanceof BigDecimal;
	}

	/**
	 * 判断值是否可解析为 ISO 日期或时间。
	 *
	 * @param value 待检查值
	 * @return 是否为日期值
	 */
	public boolean isDateValue(Object value) {
		if (value instanceof LocalDate || value instanceof LocalDateTime || value instanceof OffsetDateTime) {
			return true;
		}
		if (!(value instanceof String text)) {
			return false;
		}
		try {
			if (text.contains("T")) {
				try {
					OffsetDateTime.parse(text);
				}
				catch (DateTimeParseException ex) {
					LocalDateTime.parse(text);
				}
			}
			else if (text.contains(" ")) {
				LocalDateTime.parse(text, MYSQL_DATETIME_FORMATTER);
			}
			else {
				LocalDate.parse(text);
			}
			return true;
		}
		catch (DateTimeParseException ex) {
			return false;
		}
	}

	/**
	 * 规范化可空枚举值。
	 *
	 * @param value        原始值
	 * @param defaultValue 默认值
	 * @return 规范化结果
	 */
	private String normalize(String value, String defaultValue) {
		return value == null || value.isBlank() ? defaultValue : value;
	}

	/**
	 * 校验纯文本字段长度和危险字符。
	 *
	 * @param value     文本
	 * @param maxLength 最大长度
	 * @return 是否安全
	 */
	private boolean hasSafeText(String value, int maxLength) {
		if (value == null || value.isBlank() || value.length() > maxLength) {
			return false;
		}
		String lower = value.toLowerCase();
		return !value.contains("<") && !value.contains(">")
			&& !lower.contains("javascript:") && !lower.contains("http://") && !lower.contains("https://");
	}

	/**
	 * 按语义通道识别来源字段的数据类型族。
	 *
	 * @param channel 语义通道
	 * @param value 字段值
	 * @return 数据类型族
	 */
	private String valueFamily(String channel, Object value) {
		if (value instanceof Number) {
			return "number";
		}
		if (value instanceof Boolean) {
			return "boolean";
		}
		if (value instanceof LocalDate || value instanceof LocalDateTime || value instanceof OffsetDateTime) {
			return "date";
		}
		// 只有甘特图时间通道解析日期形字符串，普通类目文本保持字符串语义。
		if (("start".equals(channel) || "end".equals(channel)) && isDateValue(value)) {
			return "date";
		}
		return "string";
	}

	/**
	 * 校验子弹图实际值、目标值和范围值的显式单位保持一致。
	 *
	 * @param plan 图表规划
	 * @throws IllegalArgumentException 子弹图数值单位冲突时抛出
	 */
	private void validateBulletUnits(ChartPlan plan) {
		if (plan.type() != ChartType.BULLET) {
			return;
		}
		String comparableUnit = null;
		for (ChartPlan.Binding binding : plan.bindings()) {
			if (!Set.of("actual", "target", "range").contains(binding.channel())
					|| binding.unit() == null) {
				continue;
			}
			if (comparableUnit != null && !comparableUnit.equals(binding.unit())) {
				throw new IllegalArgumentException("子弹图数值字段单位冲突");
			}
			comparableUnit = binding.unit();
		}
	}

	/**
	 * 校验字段聚合声明与转换类型、分组字段保持一致。
	 *
	 * @param plan      图表规划
	 * @param transform 数据转换
	 */
	private void validateAggregateUsage(ChartPlan plan, ChartPlan.Transform transform) {
		String transformType = normalize(transform == null ? null : transform.type(), "identity");
		List<String> groupBy = transform != null && transform.groupBy() != null
			? transform.groupBy() : List.of();
		for (ChartPlan.Binding binding : plan.bindings()) {
			String aggregate = normalize(binding.aggregate(), "none");
			if (!"aggregate".equals(transformType) && !"none".equals(aggregate)) {
				throw new IllegalArgumentException("非聚合转换不能声明聚合方式");
			}
			if (!"aggregate".equals(transformType)) {
				continue;
			}
			if (groupBy.contains(binding.field()) && !"none".equals(aggregate)) {
				throw new IllegalArgumentException("聚合转换的分组字段不能声明聚合方式");
			}
			if (!groupBy.contains(binding.field()) && "none".equals(aggregate)) {
				throw new IllegalArgumentException("聚合转换的非分组字段必须声明聚合方式");
			}
		}
	}

	/**
	 * 判断指定图表的语义通道是否要求数值字段。
	 *
	 * @param chartType 图表类型
	 * @param channel   语义通道
	 * @return 是否为数值通道
	 */
	private boolean isNumericChannel(ChartType chartType, String channel) {
		return NUMERIC_CHANNELS.contains(channel)
			|| (NUMERIC_Y_TYPES.contains(chartType) && "y".equals(channel))
			|| (chartType == ChartType.BULLET && "target".equals(channel))
			|| (Set.of(ChartType.SCATTER, ChartType.BUBBLE).contains(chartType) && "x".equals(channel));
	}

}
