package com.example.rag.chat.chart.compile;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.rag.chat.chart.model.BusinessToolResult;
import com.example.rag.chat.chart.model.ChartPlan;
import com.example.rag.vo.ChartVO;
import com.example.rag.vo.ChartVO.ChartType;

import org.springframework.stereotype.Component;

/**
 * 根据模型选择的图表类型和标题，从真实业务数据自动生成完整内部规划。
 */
@Component
public class ChartPlanFactory {

	/** 标识字段语义关键字。 */
	private static final Set<String> ID_HINTS = Set.of(
		"id", "code", "number", "ticket", "order", "编号", "编码", "标识", "单号", "工单");

	/** 数值指标绑定时需要排除的标识字段命名模式。 */
	private static final Pattern IDENTIFIER_FIELD_PATTERN = Pattern.compile(
		"(?:^|[_\\-\\s])(?:id|ID|code|CODE|no|NO|number|NUMBER)$"
			+ "|(?:Id|ID|Code|CODE|No|NO|Number|NUMBER)$|(?:编号|编码|单号)$");

	/** 父级字段语义关键字。 */
	private static final Set<String> PARENT_HINTS = Set.of("parent", "pid", "父", "上级");

	/** 名称或类别字段语义关键字。 */
	private static final Set<String> NAME_HINTS = Set.of(
		"name", "label", "title", "category", "product", "customer", "supplier", "名称", "类别", "产品", "客户", "供应商");

	/** 来源节点字段语义关键字。 */
	private static final Set<String> SOURCE_HINTS = Set.of("source", "from", "来源", "起点", "流出");

	/** 目标节点字段语义关键字。 */
	private static final Set<String> TARGET_HINTS = Set.of("target", "to", "目标", "终点", "流入");

	/** 开始时间字段语义关键字。 */
	private static final Set<String> START_HINTS = Set.of("start", "begin", "开始", "起始");

	/** 结束时间字段语义关键字。 */
	private static final Set<String> END_HINTS = Set.of("end", "finish", "完成", "结束", "截止");

	/** 实际值字段语义关键字。 */
	private static final Set<String> ACTUAL_HINTS = Set.of(
		"actual", "completed", "received", "paid", "current", "实际", "完成", "已收", "已付", "当前");

	/** 目标值字段语义关键字。 */
	private static final Set<String> TARGET_VALUE_HINTS = Set.of(
		"target", "planned", "ordered", "budget", "goal", "目标", "计划", "订购", "预算");

	/** 范围字段语义关键字。 */
	private static final Set<String> RANGE_HINTS = Set.of("range", "max", "capacity", "范围", "上限", "容量");

	/** 甘特图进度字段语义关键字。 */
	private static final Set<String> PROGRESS_HINTS = Set.of(
		"progress", "completion", "completedrate", "进度", "完成率");

	/** 趋势图横轴优先识别的日期和时间字段语义关键字。 */
	private static final Set<String> TIME_AXIS_HINTS = Set.of(
		"date", "time", "day", "month", "year", "日期", "时间", "日", "月", "年");

	/** 数值字段语义关键字。 */
	private static final Set<String> VALUE_HINTS = Set.of(
		"value", "amount", "qty", "quantity", "count", "total", "rate", "price", "balance", "days",
		"数值", "金额", "数量", "总计", "比率", "单价", "余额", "天数");

	/** 标题中的前 N 名或 TOP N 行数限制。 */
	private static final Pattern TITLE_LIMIT_PATTERN = Pattern.compile(
		"(?i)(?:top\\s*|前\\s*)([1-9]\\d{0,2})(?!\\d)");

	/** 常见 ERP 字段的中文展示名称。 */
	private static final Map<String, String> COMMON_FIELD_LABELS = Map.ofEntries(
		Map.entry("ticketno", "工单号"), Map.entry("issuetype", "问题类型"),
		Map.entry("productname", "产品名称"), Map.entry("customername", "客户名称"),
		Map.entry("suppliername", "供应商名称"), Map.entry("qty", "数量"),
		Map.entry("quantity", "数量"), Map.entry("totalamount", "总金额"),
		Map.entry("receivableamount", "应收金额"), Map.entry("receivedamount", "已收金额"),
		Map.entry("payableamount", "应付金额"), Map.entry("paidamount", "已付金额"),
		Map.entry("amount", "金额"), Map.entry("balance", "余额"),
		Map.entry("agingdays", "账龄天数"), Map.entry("status", "状态"),
		Map.entry("priority", "优先级"), Map.entry("handler", "处理人"),
		Map.entry("region", "地区"), Map.entry("department", "部门"),
		Map.entry("channel", "渠道"), Map.entry("brand", "品牌"),
		Map.entry("startdate", "开始日期"), Map.entry("enddate", "结束日期"));

	/** 标题与字段的业务语义别名组。 */
	private static final List<SemanticGroup> SEMANTIC_GROUPS = List.of(
		new SemanticGroup(Set.of("数量", "销量", "件数", "次数", "库存", "产量", "volume", "quantity", "count"),
			Set.of("qty", "quantity", "count", "number", "volume", "stock", "数量", "销量", "件数", "次数", "库存", "产量")),
		new SemanticGroup(Set.of("金额", "销售额", "余额", "应收", "应付", "成本", "费用", "价格", "营收", "amount", "balance", "revenue"),
			Set.of("amount", "price", "cost", "fee", "balance", "receivable", "payable", "revenue", "金额", "余额", "应收", "应付", "成本", "费用", "价格")),
		new SemanticGroup(Set.of("比例", "占比", "百分比", "完成率", "合格率", "进度", "rate", "ratio", "percent"),
			Set.of("rate", "ratio", "percent", "progress", "比例", "占比", "完成率", "合格率", "进度")),
		new SemanticGroup(Set.of("日期", "时间", "月份", "年度", "按月", "按年", "趋势", "date", "time", "month", "year"),
			Set.of("date", "time", "month", "year", "day", "日期", "时间", "月份", "年度")),
		new SemanticGroup(Set.of("产品", "物料", "商品", "product", "item", "material"),
			Set.of("product", "item", "material", "sku", "产品", "物料", "商品")),
		new SemanticGroup(Set.of("客户", "customer", "client"), Set.of("customer", "client", "客户")),
		new SemanticGroup(Set.of("供应商", "supplier", "vendor"), Set.of("supplier", "vendor", "供应商")),
		new SemanticGroup(Set.of("状态", "处理", "解决", "status"), Set.of("status", "state", "状态")),
		new SemanticGroup(Set.of("地区", "区域", "region"), Set.of("region", "地区", "区域")),
		new SemanticGroup(Set.of("部门", "department"), Set.of("department", "部门")),
		new SemanticGroup(Set.of("渠道", "channel"), Set.of("channel", "渠道")),
		new SemanticGroup(Set.of("品牌", "brand"), Set.of("brand", "品牌")),
		new SemanticGroup(Set.of("账龄", "逾期", "天数", "aging", "overdue", "days"),
			Set.of("aging", "overdue", "days", "账龄", "逾期", "天数")));

	/** 图表规划校验器，复用既有日期识别规则。 */
	private final ChartPlanValidator validator;

	/**
	 * 创建后端图表规划生成器。
	 *
	 * @param validator 图表规划校验器
	 */
	public ChartPlanFactory(ChartPlanValidator validator) {
		this.validator = validator;
	}

	/**
	 * 为模型选择的图表类型生成按相关性排序的内部规划候选。
	 *
	 * @param type    模型选择的图表类型
	 * @param title   模型选择的图表标题
	 * @param results 本轮已捕获的真实业务结果
	 * @return 可供编译器逐个尝试的内部规划
	 */
	public List<ChartPlan> createCandidates(ChartType type, String title, List<BusinessToolResult> results) {
		if (type == null || title == null || results == null || results.isEmpty()) {
			return List.of();
		}
		List<PlanCandidate> candidates = new ArrayList<>();
		for (BusinessToolResult result : results) {
			ChartPlan plan = createPlan(type, title, result);
			if (plan != null) {
				candidates.add(new PlanCandidate(plan, relevanceScore(plan, title), result.sequence()));
			}
		}
		candidates.sort(Comparator.comparingInt(PlanCandidate::score).reversed()
			.thenComparing(Comparator.comparingInt(PlanCandidate::sequence).reversed()));
		return candidates.stream().map(PlanCandidate::plan).toList();
	}

	/**
	 * 为单个业务结果生成完整内部规划。
	 *
	 * @param type   图表类型
	 * @param title  图表标题
	 * @param result 业务结果
	 * @return 内部规划，字段结构不满足时返回 null
	 */
	private ChartPlan createPlan(ChartType type, String title, BusinessToolResult result) {
		ColumnProfile profile = profile(result.rows());
		List<ChartPlan.Binding> bindings = bindingsFor(type, title, profile, result.rows());
		if (bindings.isEmpty()) {
			return null;
		}
		ChartPlan.Transform transform = transformFor(type, title, bindings, result.rows());
		bindings = applyAggregates(bindings, transform);
		ChartVO.ChartOptions options = optionsFor(type, bindings, result.rows());
		return new ChartPlan(type, title, List.of(result.toolName()), List.of(result.sequence()),
			bindings, transform, options);
	}

	/**
	 * 分析所有数据行共同具备的标量、数值、非负数值、日期和文本字段。
	 *
	 * @param rows 业务数据行
	 * @return 字段结构画像
	 */
	private ColumnProfile profile(List<Map<String, Object>> rows) {
		if (rows == null || rows.isEmpty()) {
			return new ColumnProfile(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Map.of());
		}
		List<String> scalarFields = new ArrayList<>();
		List<String> nonNullFields = new ArrayList<>();
		List<String> numericFields = new ArrayList<>();
		List<String> nonNegativeFields = new ArrayList<>();
		List<String> dateFields = new ArrayList<>();
		List<String> textFields = new ArrayList<>();
		Map<String, Integer> fieldOrder = new LinkedHashMap<>();
		int order = 0;
		for (String field : rows.get(0).keySet()) {
			fieldOrder.put(field, order++);
			if (rows.stream().anyMatch(row -> !row.containsKey(field))
					|| rows.stream().map(row -> row.get(field)).anyMatch(value -> !isScalar(value))) {
				continue;
			}
			scalarFields.add(field);
			List<Object> values = rows.stream().map(row -> row.get(field)).toList();
			// 分类计数只能选择每行都有值的字段，避免空值导致记录数被低估。
			if (values.stream().allMatch(value -> value != null)) {
				nonNullFields.add(field);
			}
			boolean numeric = values.stream().allMatch(value -> value instanceof Number);
			boolean date = values.stream().allMatch(value -> value != null && validator.isDateValue(value));
			if (numeric) {
				numericFields.add(field);
				if (values.stream().map(value -> toDecimal((Number) value))
						.allMatch(value -> value.signum() >= 0)) {
					nonNegativeFields.add(field);
				}
			}
			else if (date) {
				dateFields.add(field);
			}
			else if (values.stream().anyMatch(value -> value != null)) {
				textFields.add(field);
			}
		}
		return new ColumnProfile(List.copyOf(scalarFields), List.copyOf(nonNullFields), List.copyOf(numericFields),
			List.copyOf(nonNegativeFields), List.copyOf(dateFields), List.copyOf(textFields),
			Map.copyOf(fieldOrder));
	}

	/**
	 * 按图表类型自动生成语义通道绑定。
	 *
	 * @param type    图表类型
	 * @param title   图表标题
	 * @param profile 字段画像
	 * @param rows    业务数据行
	 * @return 完整字段绑定，不满足类型要求时返回空列表
	 */
	private List<ChartPlan.Binding> bindingsFor(ChartType type, String title,
			ColumnProfile profile, List<Map<String, Object>> rows) {
		return switch (type) {
			case PIE, DONUT, FUNNEL, WORD_CLOUD -> nameValueBindings(title, profile, true, false);
			case BAR -> {
				List<ChartPlan.Binding> bindings = categoryValueBindings(title, profile, false);
				// 没有数值列时按分类记录数生成可信的条形图指标。
				bindings = bindings.isEmpty() ? categoryCountBindings("category", title, profile) : bindings;
				yield withOptionalSeries(bindings, title, profile, rows);
			}
			case WATERFALL -> categoryValueBindings(title, profile, false);
			case BULLET -> bulletBindings(title, profile);
			case AREA, STEP, LINE -> withOptionalSeries(
				axisBindings(title, profile, false), title, profile, rows);
			case RADAR -> multiNumericBindings("indicator", title, profile, true);
			case SCATTER -> withOptionalSeries(
				axisBindings(title, profile, true), title, profile, rows);
			case BUBBLE -> withOptionalSeries(bubbleBindings(title, profile), title, profile, rows);
			case HISTOGRAM -> singleNumericBinding("value", title, profile.numericFields(), profile);
			case BOXPLOT -> categoryValueBindings(title, profile, false);
			case HEATMAP -> heatmapBindings(title, profile);
			case SANKEY -> sankeyBindings(title, profile);
			case SUNBURST, TREEMAP -> hierarchyBindings(title, profile);
			case GANTT -> ganttBindings(title, profile, rows);
			case GAUGE, LIQUID_FILL -> rows.size() == 1
				? nameValueBindings(title, profile, true, true) : List.of();
			case PARALLEL -> multiNumericBindings("parallel", title, profile, false);
		};
	}

	/**
	 * 生成名称和值通道绑定。
	 *
	 * @param title             图表标题
	 * @param profile           字段画像
	 * @param nonNegativeValue  是否要求非负数值
	 * @param allowNumericName  缺少文本时是否允许数值字段作为单指标名称
	 * @return 字段绑定
	 */
	private List<ChartPlan.Binding> nameValueBindings(String title, ColumnProfile profile,
			boolean nonNegativeValue, boolean allowNumericName) {
		List<String> values = metricFields(
			nonNegativeValue ? profile.nonNegativeFields() : profile.numericFields());
		String value = pick(values, title, VALUE_HINTS, Set.of(), profile);
		if (value == null) {
			// 饼图等分类图允许后端直接统计结构化业务记录数，单指标图仍要求真实数值列。
			return allowNumericName ? List.of() : categoryCountBindings("name", title, profile);
		}
		List<String> names = new ArrayList<>(profile.textFields());
		if (allowNumericName && names.isEmpty()) {
			names.addAll(profile.scalarFields());
		}
		String name = pick(names, title, NAME_HINTS, Set.of(value), profile);
		if (name == null && allowNumericName) {
			name = value;
		}
		return name == null ? List.of() : List.of(binding("name", name), binding("value", value));
	}

	/**
	 * 生成类别和值通道绑定。
	 *
	 * @param title            图表标题
	 * @param profile          字段画像
	 * @param nonNegativeValue 是否要求非负数值
	 * @return 字段绑定
	 */
	private List<ChartPlan.Binding> categoryValueBindings(String title, ColumnProfile profile,
			boolean nonNegativeValue) {
		List<String> values = metricFields(
			nonNegativeValue ? profile.nonNegativeFields() : profile.numericFields());
		String value = pick(values, title, VALUE_HINTS, Set.of(), profile);
		String category = pick(categoryFields(profile), title, NAME_HINTS,
			value == null ? Set.of() : Set.of(value), profile);
		return value == null || category == null ? List.of()
			: List.of(binding("category", category), binding("value", value));
	}

	/**
	 * 生成子弹图实际值、目标值和可选范围绑定。
	 *
	 * @param title   图表标题
	 * @param profile 字段画像
	 * @return 字段绑定
	 */
	private List<ChartPlan.Binding> bulletBindings(String title, ColumnProfile profile) {
		List<String> values = rank(metricFields(profile.nonNegativeFields()),
			title, VALUE_HINTS, Set.of(), profile);
		if (values.size() < 2) {
			return List.of();
		}
		String actual = pick(values, title, ACTUAL_HINTS, Set.of(), profile);
		String target = pick(values, title, TARGET_VALUE_HINTS, Set.of(actual), profile);
		String category = pick(categoryFields(profile), title, NAME_HINTS,
			Set.of(actual, target), profile);
		if (category == null || actual == null || target == null) {
			return List.of();
		}
		List<ChartPlan.Binding> bindings = new ArrayList<>(List.of(
			binding("category", category), binding("actual", actual), binding("target", target)));
		String range = pick(values, title, RANGE_HINTS, Set.of(actual, target), profile);
		if (range != null) {
			bindings.add(binding("range", range));
		}
		return List.copyOf(bindings);
	}

	/**
	 * 生成直角坐标轴绑定。
	 *
	 * @param title       图表标题
	 * @param profile     字段画像
	 * @param numericOnly 横轴是否必须为数值
	 * @return 字段绑定
	 */
	private List<ChartPlan.Binding> axisBindings(String title, ColumnProfile profile, boolean numericOnly) {
		List<String> metrics = metricFields(profile.numericFields());
		List<String> xFields = numericOnly ? metrics : axisFields(profile);
		// 先确定业务指标，避免金额等高相关字段被误选为类别或时间横轴。
		String y = pick(metrics, title, Set.of("y", "value", "数值"), Set.of(), profile);
		Set<String> excluded = y == null ? Set.of() : Set.of(y);
		// 趋势图存在月份、日期等字段时优先作为横轴，避免分类系列抢占横轴。
		List<String> timeFields = xFields.stream()
			.filter(field -> hintScore(field, TIME_AXIS_HINTS) > 0)
			.toList();
		String x = numericOnly ? null : pick(timeFields, title, TIME_AXIS_HINTS, excluded, profile);
		if (x == null) {
			x = pick(xFields, title, Set.of("x", "date", "time", "日期", "时间"), excluded, profile);
		}
		return x == null || y == null ? List.of() : List.of(binding("x", x), binding("y", y));
	}

	/**
	 * 生成气泡图三数值通道绑定。
	 *
	 * @param title   图表标题
	 * @param profile 字段画像
	 * @return 字段绑定
	 */
	private List<ChartPlan.Binding> bubbleBindings(String title, ColumnProfile profile) {
		List<String> metrics = metricFields(profile.numericFields());
		List<String> nonNegativeMetrics = metricFields(profile.nonNegativeFields());
		if (metrics.size() < 3 || nonNegativeMetrics.isEmpty()) {
			return List.of();
		}
		String size = pick(nonNegativeMetrics, title, Set.of("size", "bubble", "大小", "规模"),
			Set.of(), profile);
		List<String> axes = rank(metrics, title, VALUE_HINTS, Set.of(size), profile);
		return size == null || axes.size() < 2 ? List.of()
			: List.of(binding("x", axes.get(0)), binding("y", axes.get(1)), binding("size", size));
	}

	/**
	 * 生成雷达图或平行坐标图的多指标绑定。
	 *
	 * @param channel             多字段语义通道
	 * @param title               图表标题
	 * @param profile             字段画像
	 * @param nonNegativeRequired 是否要求非负指标
	 * @return 字段绑定
	 */
	private List<ChartPlan.Binding> multiNumericBindings(String channel, String title,
			ColumnProfile profile, boolean nonNegativeRequired) {
		List<String> metrics = rank(metricFields(
			nonNegativeRequired ? profile.nonNegativeFields() : profile.numericFields()),
			title, VALUE_HINTS, Set.of(), profile);
		String name = pick(categoryFields(profile), title, NAME_HINTS, new HashSet<>(metrics), profile);
		if (name == null || metrics.size() < 3) {
			return List.of();
		}
		List<ChartPlan.Binding> bindings = new ArrayList<>();
		bindings.add(binding("name", name));
		metrics.forEach(field -> bindings.add(binding(channel, field)));
		return List.copyOf(bindings);
	}

	/**
	 * 生成热力图坐标和值绑定。
	 *
	 * @param title   图表标题
	 * @param profile 字段画像
	 * @return 字段绑定
	 */
	private List<ChartPlan.Binding> heatmapBindings(String title, ColumnProfile profile) {
		String value = pick(metricFields(profile.numericFields()), title, VALUE_HINTS, Set.of(), profile);
		List<String> coordinates = rank(profile.scalarFields(), title, NAME_HINTS,
			value == null ? Set.of() : Set.of(value), profile);
		return value == null || coordinates.size() < 2 ? List.of()
			: List.of(binding("x", coordinates.get(0)), binding("y", coordinates.get(1)), binding("value", value));
	}

	/**
	 * 生成桑基图来源、目标和值绑定。
	 *
	 * @param title   图表标题
	 * @param profile 字段画像
	 * @return 字段绑定
	 */
	private List<ChartPlan.Binding> sankeyBindings(String title, ColumnProfile profile) {
		String source = pickSemantic(profile.textFields(), title, SOURCE_HINTS, Set.of(), profile);
		String target = pickSemantic(profile.textFields(), title, TARGET_HINTS,
			source == null ? Set.of() : Set.of(source), profile);
		String value = pick(metricFields(profile.nonNegativeFields()), title, VALUE_HINTS, Set.of(), profile);
		return source == null || target == null || value == null ? List.of()
			: List.of(binding("source", source), binding("target", target), binding("value", value));
	}

	/**
	 * 生成旭日图或矩形树图的层级绑定。
	 *
	 * @param title   图表标题
	 * @param profile 字段画像
	 * @return 字段绑定
	 */
	private List<ChartPlan.Binding> hierarchyBindings(String title, ColumnProfile profile) {
		String parent = pickSemantic(profile.scalarFields(), title, PARENT_HINTS, Set.of(), profile);
		String id = pickSemantic(profile.scalarFields(), title, ID_HINTS,
			parent == null ? Set.of() : Set.of(parent), profile);
		String name = pick(profile.textFields(), title, NAME_HINTS,
			setOfNonNull(parent), profile);
		if (name == null) {
			name = id;
		}
		String value = pick(metricFields(profile.nonNegativeFields()), title, VALUE_HINTS, Set.of(), profile);
		return id == null || parent == null || name == null || value == null ? List.of()
			: List.of(binding("id", id), binding("parentId", parent),
				binding("name", name), binding("value", value));
	}

	/**
	 * 生成甘特图任务、开始和结束时间绑定。
	 *
	 * @param title   图表标题
	 * @param profile 字段画像
	 * @param rows    业务数据行
	 * @return 字段绑定
	 */
	private List<ChartPlan.Binding> ganttBindings(String title, ColumnProfile profile,
			List<Map<String, Object>> rows) {
		String start = pickSemantic(profile.dateFields(), title, START_HINTS, Set.of(), profile);
		String end = pickSemantic(profile.dateFields(), title, END_HINTS,
			start == null ? Set.of() : Set.of(start), profile);
		// 甘特任务类别优先从文本字段选择，防止“进度”标题把数值进度误当成任务名称。
		String category = pick(profile.textFields(), title, NAME_HINTS, setOfNonNull(start, end), profile);
		if (category == null) {
			List<String> fallbackCategories = profile.scalarFields().stream()
				.filter(field -> hintScore(field, PROGRESS_HINTS) == 0)
				.toList();
			category = pick(fallbackCategories, title, NAME_HINTS, setOfNonNull(start, end), profile);
		}
		if (start == null || end == null || category == null) {
			return List.of();
		}
		List<ChartPlan.Binding> bindings = new ArrayList<>(List.of(
			binding("category", category), binding("start", start), binding("end", end)));
		List<String> progressFields = profile.nonNegativeFields().stream()
			.filter(field -> valuesInUnitInterval(rows, field))
			.toList();
		String progress = pickSemantic(progressFields, title, PROGRESS_HINTS,
			Set.of(category, start, end), profile);
		if (progress != null) {
			bindings.add(binding("progress", progress));
		}
		return List.copyOf(bindings);
	}

	/**
	 * 生成仅需要一个数值通道的绑定。
	 *
	 * @param channel 数值通道
	 * @param title   图表标题
	 * @param fields  可选数值字段
	 * @param profile 字段画像
	 * @return 字段绑定
	 */
	private List<ChartPlan.Binding> singleNumericBinding(String channel, String title,
			List<String> fields, ColumnProfile profile) {
		String field = pick(metricFields(fields), title, VALUE_HINTS, Set.of(), profile);
		return field == null ? List.of() : List.of(binding(channel, field));
	}

	/**
	 * 根据图表类型生成后端受控转换、排序和行数限制。
	 *
	 * @param type     图表类型
	 * @param title    图表标题
	 * @param bindings 字段绑定
	 * @param rows     业务数据行
	 * @return 安全转换配置
	 */
	private ChartPlan.Transform transformFor(ChartType type, String title,
			List<ChartPlan.Binding> bindings, List<Map<String, Object>> rows) {
		boolean countAggregate = bindings.stream()
			.anyMatch(binding -> "count".equals(binding.aggregate()));
		List<String> groupBy = switch (type) {
			case PIE, DONUT, FUNNEL, WORD_CLOUD -> countAggregate
				? List.of(fieldFor(bindings, "name")) : List.of();
			case BAR -> groupingFields(bindings, "category");
			case AREA, STEP, LINE -> groupingFields(bindings, "x");
			case HEATMAP -> List.of(fieldFor(bindings, "x"), fieldFor(bindings, "y"));
			default -> List.of();
		};
		// 显式计数必须聚合；普通数值仅在分组重复时聚合。
		boolean aggregate = countAggregate || (!groupBy.isEmpty() && hasDuplicateGroups(rows, groupBy));
		String sortBy = null;
		String sortDirection = null;
		if (Set.of(ChartType.PIE, ChartType.DONUT, ChartType.BAR,
				ChartType.FUNNEL, ChartType.WORD_CLOUD).contains(type)) {
			sortBy = fieldFor(bindings, "value");
			sortDirection = "desc";
		}
		else if (Set.of(ChartType.AREA, ChartType.STEP, ChartType.LINE).contains(type)) {
			sortBy = fieldFor(bindings, "x");
			sortDirection = "asc";
		}
		else if (type == ChartType.GANTT) {
			sortBy = fieldFor(bindings, "start");
			sortDirection = "asc";
		}
		return new ChartPlan.Transform(aggregate ? "aggregate" : "identity",
			aggregate ? groupBy : List.of(), sortBy, sortDirection, limitFor(title));
	}

	/**
	 * 根据聚合转换为分组字段和指标字段补充后端权威聚合方式。
	 *
	 * @param bindings 字段绑定
	 * @param transform 数据转换
	 * @return 已补充聚合声明的字段绑定
	 */
	private List<ChartPlan.Binding> applyAggregates(List<ChartPlan.Binding> bindings,
			ChartPlan.Transform transform) {
		if (!"aggregate".equals(transform.type())) {
			return bindings;
		}
		Set<String> groupBy = new HashSet<>(transform.groupBy());
		return bindings.stream().map(binding -> {
			String declaredAggregate = binding.aggregate() == null || binding.aggregate().isBlank()
				? "none" : binding.aggregate();
			// 保留后端生成的 count 声明，其余指标仍按字段语义选择 sum 或 avg。
			String aggregate = groupBy.contains(binding.field()) ? "none"
				: "none".equals(declaredAggregate) ? aggregateFor(binding.field()) : declaredAggregate;
			return new ChartPlan.Binding(binding.channel(), binding.field(), binding.label(), aggregate, binding.unit());
		})
			.toList();
	}

	/**
	 * 判断分组字段组合在来源数据中是否重复。
	 *
	 * @param rows    业务数据行
	 * @param groupBy 分组字段
	 * @return 是否需要聚合
	 */
	private boolean hasDuplicateGroups(List<Map<String, Object>> rows, List<String> groupBy) {
		Set<List<Object>> keys = new HashSet<>();
		for (Map<String, Object> row : rows) {
			List<Object> key = groupBy.stream().map(row::get).toList();
			if (!keys.add(key)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 根据字段语义选择求和或平均聚合，比例和平均值字段使用平均，其余指标使用求和。
	 *
	 * @param field 指标字段
	 * @return 聚合方式
	 */
	private String aggregateFor(String field) {
		String normalized = normalize(field);
		return containsAny(normalized, Set.of(
			"rate", "ratio", "percent", "average", "avg", "率", "比例", "平均")) ? "avg" : "sum";
	}

	/**
	 * 从标题提取前 N 名限制，缺失时保持协议最大行数。
	 *
	 * @param title 图表标题
	 * @return 1 到 50 的安全行数限制
	 */
	private int limitFor(String title) {
		Matcher matcher = TITLE_LIMIT_PATTERN.matcher(title == null ? "" : title);
		if (!matcher.find()) {
			return 50;
		}
		return Math.min(50, Integer.parseInt(matcher.group(1)));
	}

	/**
	 * 根据图表类型和真实数值生成后端安全展示选项。
	 *
	 * @param type     图表类型
	 * @param bindings 字段绑定
	 * @param rows     业务数据行
	 * @return 安全展示选项
	 */
	private ChartVO.ChartOptions optionsFor(ChartType type, List<ChartPlan.Binding> bindings,
			List<Map<String, Object>> rows) {
		String orientation = type == ChartType.BAR ? "vertical" : null;
		Boolean stacked = Set.of(ChartType.BAR, ChartType.AREA, ChartType.STEP, ChartType.LINE).contains(type)
			? Boolean.FALSE : null;
		Boolean smooth = Set.of(ChartType.AREA, ChartType.LINE).contains(type) ? Boolean.TRUE : null;
		String step = type == ChartType.STEP ? "middle" : null;
		Double min = null;
		Double max = null;
		if (type == ChartType.GAUGE || type == ChartType.LIQUID_FILL) {
			String valueField = fieldFor(bindings, "value");
			double maximum = rows.stream().map(row -> row.get(valueField))
				.filter(Number.class::isInstance).map(Number.class::cast)
				.mapToDouble(Number::doubleValue).max().orElse(0.0);
			if (Double.isFinite(maximum)) {
				min = 0.0;
				// 水位值按比例或百分比选择可信上界，超过 100 的数据由编译器拒绝并降级为文本。
				max = type == ChartType.GAUGE ? Math.max(100.0, maximum * 1.1)
					: maximum <= 1.0 ? 1.0 : 100.0;
			}
		}
		String sort = Set.of(ChartType.PIE, ChartType.DONUT, ChartType.BAR,
			ChartType.FUNNEL, ChartType.WORD_CLOUD).contains(type) ? "desc" : null;
		Boolean showLabel = Set.of(ChartType.PIE, ChartType.DONUT, ChartType.BAR,
			ChartType.FUNNEL, ChartType.WATERFALL, ChartType.BULLET).contains(type) ? Boolean.TRUE : null;
		return new ChartVO.ChartOptions(orientation, stacked, smooth, step,
			null, min, max, null, sort, showLabel);
	}

	/**
	 * 生成单个内部字段绑定。
	 *
	 * @param channel 语义通道
	 * @param field   来源字段
	 * @return 字段绑定
	 */
	private ChartPlan.Binding binding(String channel, String field) {
		return new ChartPlan.Binding(channel, field, labelFor(field, channel), "none", null);
	}

	/**
	 * 返回类别候选字段，文本字段优先于日期和其他标量字段。
	 *
	 * @param profile 字段画像
	 * @return 去重后的类别候选字段
	 */
	private List<String> categoryFields(ColumnProfile profile) {
		LinkedHashSet<String> fields = new LinkedHashSet<>(profile.textFields());
		fields.addAll(profile.dateFields());
		fields.addAll(profile.scalarFields());
		return List.copyOf(fields);
	}

	/**
	 * 返回横轴候选字段，日期优先于文本和其他标量字段。
	 *
	 * @param profile 字段画像
	 * @return 去重后的横轴候选字段
	 */
	private List<String> axisFields(ColumnProfile profile) {
		LinkedHashSet<String> fields = new LinkedHashSet<>(profile.dateFields());
		fields.addAll(profile.textFields());
		fields.addAll(profile.scalarFields());
		return List.copyOf(fields);
	}

	/**
	 * 选择相关性最高且未被占用的字段。
	 *
	 * @param fields   候选字段
	 * @param title    图表标题
	 * @param hints    当前通道关键字
	 * @param excluded 已占用字段
	 * @param profile  字段画像
	 * @return 最佳字段，没有时返回 null
	 */
	private String pick(List<String> fields, String title, Set<String> hints,
			Set<String> excluded, ColumnProfile profile) {
		List<String> ranked = rank(fields, title, hints, excluded, profile);
		return ranked.isEmpty() ? null : ranked.get(0);
	}

	/**
	 * 选择必须命中当前通道语义关键字的字段。
	 *
	 * @param fields   候选字段
	 * @param title    图表标题
	 * @param hints    当前通道关键字
	 * @param excluded 已占用字段
	 * @param profile  字段画像
	 * @return 语义匹配字段，没有时返回 null
	 */
	private String pickSemantic(List<String> fields, String title, Set<String> hints,
			Set<String> excluded, ColumnProfile profile) {
		String field = pick(fields, title, hints, excluded, profile);
		return field != null && hintScore(field, hints) > 0 ? field : null;
	}

	/**
	 * 按标题相关性、通道语义和来源字段顺序排列候选字段。
	 *
	 * @param fields   候选字段
	 * @param title    图表标题
	 * @param hints    当前通道关键字
	 * @param excluded 已占用字段
	 * @param profile  字段画像
	 * @return 已排序字段
	 */
	private List<String> rank(List<String> fields, String title, Set<String> hints,
			Set<String> excluded, ColumnProfile profile) {
		return fields.stream().filter(field -> !excluded.contains(field))
			.sorted(Comparator.comparingInt((String field) -> fieldScore(field, title, hints)).reversed()
				.thenComparingInt(field -> profile.fieldOrder().getOrDefault(field, Integer.MAX_VALUE)))
			.toList();
	}

	/**
	 * 计算内部规划与标题的整体相关性，用于多 Tool 结果自动选源。
	 *
	 * @param plan  内部规划
	 * @param title 图表标题
	 * @return 相关性分数
	 */
	private int relevanceScore(ChartPlan plan, String title) {
		return plan.bindings().stream()
			.mapToInt(binding -> fieldScore(binding.field(), title, Set.of()))
			.sum();
	}

	/**
	 * 计算字段与标题和通道提示的匹配分数。
	 *
	 * @param field 字段名
	 * @param title 图表标题
	 * @param hints 当前通道关键字
	 * @return 匹配分数
	 */
	private int fieldScore(String field, String title, Set<String> hints) {
		String normalizedField = normalize(field);
		String normalizedTitle = normalize(title);
		int score = hintScore(field, hints) * 20;
		String commonLabel = COMMON_FIELD_LABELS.get(normalizedField);
		if (commonLabel != null && normalizedTitle.contains(normalize(commonLabel))) {
			score += 150;
		}
		for (SemanticGroup group : SEMANTIC_GROUPS) {
			if (containsAny(normalizedTitle, group.titleHints())
					&& containsAny(normalizedField, group.fieldHints())) {
				score += 100;
			}
		}
		return score;
	}

	/**
	 * 计算字段命中当前通道关键字的数量。
	 *
	 * @param field 字段名
	 * @param hints 通道关键字
	 * @return 命中数量
	 */
	private int hintScore(String field, Set<String> hints) {
		String normalizedField = normalize(field);
		return (int) hints.stream().map(this::normalize).filter(normalizedField::contains).count();
	}

	/**
	 * 判断文本是否包含任一已规范化关键字。
	 *
	 * @param text  已规范化文本
	 * @param hints 关键字
	 * @return 是否命中
	 */
	private boolean containsAny(String text, Set<String> hints) {
		return hints.stream().map(this::normalize).anyMatch(text::contains);
	}

	/**
	 * 将字段名或标题规范化为不含分隔符的小写文本。
	 *
	 * @param value 原始文本
	 * @return 规范化文本
	 */
	private String normalize(String value) {
		return value == null ? "" : value.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "").replace(" ", "");
	}

	/**
	 * 根据来源字段生成安全、稳定的展示标签。
	 *
	 * @param field   来源字段
	 * @param channel 语义通道
	 * @return 展示标签
	 */
	private String labelFor(String field, String channel) {
		String label = COMMON_FIELD_LABELS.get(normalize(field));
		if (label != null) {
			return label;
		}
		if (field.chars().anyMatch(character -> Character.UnicodeScript.of(character)
				== Character.UnicodeScript.HAN)) {
			return field;
		}
		return switch (channel) {
			case "name", "category" -> "类别";
			case "value", "actual", "target", "range", "y", "size" -> "数值";
			case "start" -> "开始时间";
			case "end" -> "结束时间";
			default -> field.replace('_', ' ');
		};
	}

	/**
	 * 从绑定中读取指定语义通道的来源字段。
	 *
	 * @param bindings 字段绑定
	 * @param channel  语义通道
	 * @return 来源字段，没有时返回 null
	 */
	private String fieldFor(List<ChartPlan.Binding> bindings, String channel) {
		return bindings.stream().filter(binding -> channel.equals(binding.channel()))
			.map(ChartPlan.Binding::field).findFirst().orElse(null);
	}

	/**
	 * 判断 Tool 单元格是否可作为图表标量字段。
	 *
	 * @param value 单元格值
	 * @return 是否为标量
	 */
	private boolean isScalar(Object value) {
		return value == null || value instanceof String || value instanceof Number || value instanceof Boolean;
	}

	/**
	 * 将 Number 安全转换为 BigDecimal 进行符号判断。
	 *
	 * @param value 数值
	 * @return BigDecimal 数值
	 */
	private BigDecimal toDecimal(Number value) {
		return value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
	}

	/**
	 * 将非空字段合并为不可变集合。
	 *
	 * @param values 可空字段
	 * @return 非空字段集合
	 */
	private Set<String> setOfNonNull(String... values) {
		Set<String> fields = new HashSet<>();
		for (String value : values) {
			if (value != null) {
				fields.add(value);
			}
		}
		return Set.copyOf(fields);
	}

	/**
	 * 单个业务结果生成的规划候选。
	 *
	 * @param plan     内部规划
	 * @param score    标题相关性分数
	 * @param sequence Tool 完成顺序
	 */
	private record PlanCandidate(ChartPlan plan, int score, int sequence) {
	}

	/**
	 * 业务结果字段结构画像。
	 *
	 * @param scalarFields      共同标量字段
	 * @param nonNullFields     每行都有值的共同字段
	 * @param numericFields     共同非空数值字段
	 * @param nonNegativeFields 共同非负数值字段
	 * @param dateFields        共同日期字段
	 * @param textFields        共同文本字段
	 * @param fieldOrder        来源字段顺序
	 */
	private record ColumnProfile(List<String> scalarFields, List<String> nonNullFields, List<String> numericFields,
			List<String> nonNegativeFields, List<String> dateFields, List<String> textFields,
			Map<String, Integer> fieldOrder) {
	}

	/**
	 * 标题与字段语义别名组。
	 *
	 * @param titleHints 标题关键字
	 * @param fieldHints 字段关键字
	 */
	private record SemanticGroup(Set<String> titleHints, Set<String> fieldHints) {
	}

	/**
	 * 为没有数值列的多行分类数据生成按记录数聚合的字段绑定。
	 *
	 * @param categoryChannel 分类语义通道
	 * @param title           图表标题
	 * @param profile         字段画像
	 * @return 分类字段与计数字段绑定，缺少独立非空计数字段时返回空列表
	 */
	private List<ChartPlan.Binding> categoryCountBindings(String categoryChannel, String title,
			ColumnProfile profile) {
		String category = pick(categoryFields(profile), title, NAME_HINTS, Set.of(), profile);
		if (category == null) {
			return List.of();
		}
		String countField = pickSemantic(profile.nonNullFields(), title, ID_HINTS, Set.of(category), profile);
		if (countField == null) {
			countField = pick(profile.nonNullFields(), title, ID_HINTS, Set.of(category), profile);
		}
		if (countField == null) {
			return List.of();
		}
		// count 只统计当前结构化结果的非空行，不采信 LLM 在回答文本中推导的数字。
		return List.of(binding(categoryChannel, category),
			new ChartPlan.Binding("value", countField, "数量", "count", null));
	}

	/**
	 * 在标题明确要求分类对比时，为直角坐标图补充可选系列字段。
	 *
	 * @param bindings 已生成的基础绑定
	 * @param title    图表标题
	 * @param profile  字段画像
	 * @param rows     业务数据行
	 * @return 包含可选系列字段的不可变绑定
	 */
	private List<ChartPlan.Binding> withOptionalSeries(List<ChartPlan.Binding> bindings,
			String title, ColumnProfile profile, List<Map<String, Object>> rows) {
		if (bindings.isEmpty()) {
			return bindings;
		}
		Set<String> usedFields = bindings.stream()
			.map(ChartPlan.Binding::field)
			.collect(java.util.stream.Collectors.toSet());
		List<String> candidates = profile.textFields().stream()
			.filter(field -> !usedFields.contains(field))
			.filter(field -> {
				int distinctCount = distinctValueCount(rows, field);
				return distinctCount >= 2 && distinctCount <= 12;
			})
			.toList();
		String series = candidates.stream()
			.sorted(Comparator.comparingInt((String field) -> seriesFieldScore(field, title)).reversed()
				.thenComparingInt(field -> profile.fieldOrder().getOrDefault(field, Integer.MAX_VALUE)))
			.findFirst().orElse(null);
		if (series == null || seriesFieldScore(series, title) <= 0) {
			return bindings;
		}
		List<ChartPlan.Binding> completedBindings = new ArrayList<>(bindings);
		completedBindings.add(binding("series", series));
		return List.copyOf(completedBindings);
	}

	/**
	 * 生成聚合所需的主轴字段和可选系列字段联合分组。
	 *
	 * @param bindings      字段绑定
	 * @param primaryChannel 主轴语义通道
	 * @return 去重后的联合分组字段
	 */
	private List<String> groupingFields(List<ChartPlan.Binding> bindings, String primaryChannel) {
		List<String> fields = new ArrayList<>();
		fields.add(fieldFor(bindings, primaryChannel));
		String series = fieldFor(bindings, "series");
		if (series != null) {
			fields.add(series);
		}
		return List.copyOf(fields);
	}

	/**
	 * 统计指定字段的非空去重值数量。
	 *
	 * @param rows  业务数据行
	 * @param field 字段名
	 * @return 非空去重值数量
	 */
	private int distinctValueCount(List<Map<String, Object>> rows, String field) {
		return (int) rows.stream()
			.map(row -> row.get(field))
			.filter(value -> value != null && !String.valueOf(value).isBlank())
			.map(String::valueOf)
			.distinct()
			.count();
	}

	/**
	 * 判断数值字段是否全部位于甘特进度协议要求的 0～1 区间。
	 *
	 * @param rows  业务数据行
	 * @param field 字段名
	 * @return 是否为完整的 0～1 数值字段
	 */
	private boolean valuesInUnitInterval(List<Map<String, Object>> rows, String field) {
		return rows.stream().allMatch(row -> {
			Object value = row.get(field);
			if (!(value instanceof Number number)) {
				return false;
			}
			BigDecimal decimal = toDecimal(number);
			return decimal.compareTo(BigDecimal.ZERO) >= 0
				&& decimal.compareTo(BigDecimal.ONE) <= 0;
		});
	}

	/**
	 * 从数值字段中排除订单号、工单号等不具备度量意义的业务标识。
	 *
	 * @param fields 共同非空数值字段
	 * @return 可作为图表数值指标的字段
	 */
	private List<String> metricFields(List<String> fields) {
		return fields.stream().filter(field -> !isIdentifierField(field)).toList();
	}

	/**
	 * 判断字段名是否表达编号、编码或单号等业务标识。
	 *
	 * @param field 字段名
	 * @return 是否为业务标识字段
	 */
	private boolean isIdentifierField(String field) {
		return field != null && IDENTIFIER_FIELD_PATTERN.matcher(field).find();
	}

	/**
	 * 计算分类字段作为系列维度时与标题的匹配分数。
	 *
	 * @param field 字段名
	 * @param title 图表标题
	 * @return 系列维度匹配分数
	 */
	private int seriesFieldScore(String field, String title) {
		String normalizedField = normalize(field);
		String normalizedTitle = normalize(title);
		int score = fieldScore(field, title, Set.of());
		// 标题直接包含来源字段名时，明确视为自定义系列维度。
		return normalizedField.length() >= 2 && normalizedTitle.contains(normalizedField)
			? score + 180 : score;
	}

}
