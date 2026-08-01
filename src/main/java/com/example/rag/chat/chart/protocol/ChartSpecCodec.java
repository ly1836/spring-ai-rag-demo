package com.example.rag.chat.chart.protocol;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAccessor;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.alibaba.fastjson2.JSON;
import com.example.rag.vo.ChartVO;
import com.example.rag.vo.ChartVO.ChartType;

import org.springframework.stereotype.Component;

/**
 * 图表协议 JSON 编解码器，统一执行版本、类型和数据规模校验。
 */
@Component
public class ChartSpecCodec {

	/** 图表 JSON 最大 UTF-8 字节数。 */
	public static final int MAX_JSON_BYTES = 60 * 1024;

	/** 图表最大数据行数。 */
	public static final int MAX_ROWS = 50;

	/** 图表最大维度数。 */
	public static final int MAX_DIMENSIONS = 32;

	/** 各协议图表类型必须包含的语义通道。 */
	private static final Map<ChartType, Set<String>> REQUIRED_CHANNELS = Map.ofEntries(
		Map.entry(ChartType.PIE, Set.of("name", "value")),
		Map.entry(ChartType.DONUT, Set.of("name", "value")),
		Map.entry(ChartType.SUNBURST, Set.of("id", "parentId", "name", "value")),
		Map.entry(ChartType.BAR, Set.of("category", "value")),
		Map.entry(ChartType.WATERFALL, Set.of("category", "base", "increase", "decrease", "value")),
		Map.entry(ChartType.BULLET, Set.of("category", "actual", "target")),
		Map.entry(ChartType.AREA, Set.of("x", "y")),
		Map.entry(ChartType.STEP, Set.of("x", "y")),
		Map.entry(ChartType.RADAR, Set.of("name", "indicator", "indicatorMax")),
		Map.entry(ChartType.SCATTER, Set.of("x", "y")),
		Map.entry(ChartType.BUBBLE, Set.of("x", "y", "size", "visualSize")),
		Map.entry(ChartType.HISTOGRAM, Set.of("category", "value")),
		Map.entry(ChartType.BOXPLOT, Set.of("category", "value", "outlier")),
		Map.entry(ChartType.HEATMAP, Set.of("x", "y", "value")),
		Map.entry(ChartType.SANKEY, Set.of("source", "target", "value")),
		Map.entry(ChartType.TREEMAP, Set.of("id", "parentId", "name", "value")),
		Map.entry(ChartType.GANTT, Set.of("category", "start", "end")),
		Map.entry(ChartType.FUNNEL, Set.of("name", "value")),
		Map.entry(ChartType.WORD_CLOUD, Set.of("name", "value")),
		Map.entry(ChartType.GAUGE, Set.of("name", "value")),
		Map.entry(ChartType.LIQUID_FILL, Set.of("name", "value", "normalized")),
		Map.entry(ChartType.PARALLEL, Set.of("name", "parallel")),
		Map.entry(ChartType.LINE, Set.of("x", "y")));

	/** 各协议图表类型允许附加的可选语义通道。 */
	private static final Map<ChartType, Set<String>> OPTIONAL_CHANNELS = Map.ofEntries(
		Map.entry(ChartType.BAR, Set.of("series")),
		Map.entry(ChartType.BULLET, Set.of("range")),
		Map.entry(ChartType.AREA, Set.of("series")),
		Map.entry(ChartType.STEP, Set.of("series")),
		Map.entry(ChartType.SCATTER, Set.of("series")),
		Map.entry(ChartType.BUBBLE, Set.of("series")),
		Map.entry(ChartType.GANTT, Set.of("progress")),
		Map.entry(ChartType.LINE, Set.of("series")));

	/** 各协议图表类型必须绑定数值维度的语义通道。 */
	private static final Map<ChartType, Set<String>> NUMERIC_CHANNELS = Map.ofEntries(
		Map.entry(ChartType.PIE, Set.of("value")),
		Map.entry(ChartType.DONUT, Set.of("value")),
		Map.entry(ChartType.SUNBURST, Set.of("value")),
		Map.entry(ChartType.BAR, Set.of("value")),
		Map.entry(ChartType.WATERFALL, Set.of("base", "increase", "decrease", "value")),
		Map.entry(ChartType.BULLET, Set.of("actual", "target", "range")),
		Map.entry(ChartType.AREA, Set.of("y")),
		Map.entry(ChartType.STEP, Set.of("y")),
		Map.entry(ChartType.RADAR, Set.of("indicator", "indicatorMax")),
		Map.entry(ChartType.SCATTER, Set.of("x", "y")),
		Map.entry(ChartType.BUBBLE, Set.of("x", "y", "size", "visualSize")),
		Map.entry(ChartType.HISTOGRAM, Set.of("value")),
		Map.entry(ChartType.BOXPLOT, Set.of("value")),
		Map.entry(ChartType.HEATMAP, Set.of("value")),
		Map.entry(ChartType.SANKEY, Set.of("value")),
		Map.entry(ChartType.TREEMAP, Set.of("value")),
		Map.entry(ChartType.GANTT, Set.of("progress")),
		Map.entry(ChartType.FUNNEL, Set.of("value")),
		Map.entry(ChartType.WORD_CLOUD, Set.of("value")),
		Map.entry(ChartType.GAUGE, Set.of("value")),
		Map.entry(ChartType.LIQUID_FILL, Set.of("value", "normalized")),
		Map.entry(ChartType.PARALLEL, Set.of("parallel")),
		Map.entry(ChartType.LINE, Set.of("y")));

	/**
	 * 将图表协议编码为 JSON。
	 *
	 * @param chart 图表协议
	 * @return 图表 JSON
	 * @throws IllegalArgumentException 图表协议非法或超出大小限制时抛出
	 */
	public String encode(ChartVO.ChartSpec chart) {
		validate(chart);
		String json = JSON.toJSONString(chart);
		validateJsonSize(json);
		return json;
	}

	/**
	 * 将 JSON 解码为图表协议。
	 *
	 * @param json 图表 JSON
	 * @return 图表协议，空字符串返回 null
	 * @throws IllegalArgumentException JSON 或图表协议非法时抛出
	 */
	public ChartVO.ChartSpec decode(String json) {
		if (json == null || json.isBlank()) {
			return null;
		}
		try {
			validateJsonSize(json);
			ChartVO.ChartSpec chart = JSON.parseObject(json, ChartVO.ChartSpec.class);
			validate(chart);
			return chart;
		}
		catch (IllegalArgumentException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("图表数据不是合法 JSON", ex);
		}
	}

	/**
	 * 校验图表协议基础结构。
	 *
	 * @param chart 图表协议
	 * @throws IllegalArgumentException 图表协议非法时抛出
	 */
	public void validate(ChartVO.ChartSpec chart) {
		if (chart == null) {
			throw new IllegalArgumentException("图表数据不能为空");
		}
		if (!ChartVO.SCHEMA_VERSION.equals(chart.schemaVersion())) {
			throw new IllegalArgumentException("不支持的图表协议版本");
		}
		if (chart.type() == null) {
			throw new IllegalArgumentException("不支持的图表类型");
		}
		if (chart.chartId() == null || chart.chartId().isBlank()) {
			throw new IllegalArgumentException("图表标识不能为空");
		}
		if (chart.chartId().length() > 120) {
			throw new IllegalArgumentException("图表标识长度超过限制");
		}
		if (chart.title() == null || chart.title().isBlank() || chart.title().length() > 120) {
			throw new IllegalArgumentException("图表标题长度必须为1到120个字符");
		}
		if (chart.subtitle() != null && chart.subtitle().length() > 160) {
			throw new IllegalArgumentException("图表副标题长度超过限制");
		}
		if (chart.dataset() == null || chart.dataset().dimensions() == null || chart.dataset().rows() == null) {
			throw new IllegalArgumentException("图表数据集不能为空");
		}
		if (chart.dataset().dimensions().isEmpty() || chart.dataset().rows().isEmpty()) {
			throw new IllegalArgumentException("图表数据集不能为空");
		}
		if (chart.dataset().dimensions().size() > MAX_DIMENSIONS) {
			throw new IllegalArgumentException("图表维度数量超过限制");
		}
		if (chart.dataset().rows().size() > MAX_ROWS) {
			throw new IllegalArgumentException("图表数据行数超过限制");
		}
		Map<String, ChartVO.Dimension> dimensions = validateDimensions(chart);
		validateEncoding(chart, dimensions.keySet());
		validateSemanticChannelTypes(chart, dimensions);
		ChartOptionsValidator.validate(chart.options());
		validateRows(chart, dimensions);
		validateRadarSemantics(chart);
		validateSource(chart.source());
	}

	/**
	 * 校验图表维度定义。
	 *
	 * @param chart 图表协议
	 */
	private Map<String, ChartVO.Dimension> validateDimensions(ChartVO.ChartSpec chart) {
		Set<String> keys = new HashSet<>();
		Map<String, ChartVO.Dimension> dimensions = new LinkedHashMap<>();
		Set<String> dataTypes = Set.of("string", "number", "date", "datetime", "boolean");
		for (ChartVO.Dimension dimension : chart.dataset().dimensions()) {
			if (dimension == null || dimension.key() == null || dimension.key().isBlank()
					|| dimension.key().length() > 120) {
				throw new IllegalArgumentException("图表维度键不能为空");
			}
			if (!keys.add(dimension.key())) {
				throw new IllegalArgumentException("图表维度键不能重复");
			}
			if (!dataTypes.contains(dimension.dataType())) {
				throw new IllegalArgumentException("图表维度数据类型不受支持");
			}
			if (dimension.label() == null || dimension.label().isBlank() || dimension.label().length() > 120) {
				throw new IllegalArgumentException("图表维度名称不合法");
			}
			if (dimension.unit() != null && dimension.unit().length() > 20) {
				throw new IllegalArgumentException("图表维度单位长度超过限制");
			}
			dimensions.put(dimension.key(), dimension);
		}
		return dimensions;
	}

	/**
	 * 校验图表 JSON 大小。
	 *
	 * @param json 图表 JSON
	 */
	private void validateJsonSize(String json) {
		if (json.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
			throw new IllegalArgumentException("图表数据大小超过限制");
		}
	}

	/**
	 * 校验图表类型对应的语义通道、字段引用和通道基数。
	 *
	 * @param chart 图表协议
	 * @param dimensionKeys 已声明维度键
	 */
	private void validateEncoding(ChartVO.ChartSpec chart, Set<String> dimensionKeys) {
		if (chart.encoding() == null) {
			throw new IllegalArgumentException("图表语义编码不能为空");
		}
		Set<String> required = REQUIRED_CHANNELS.get(chart.type());
		Set<String> allowed = new HashSet<>(required);
		allowed.addAll(OPTIONAL_CHANNELS.getOrDefault(chart.type(), Set.of()));
		if (!chart.encoding().keySet().containsAll(required)
				|| !allowed.containsAll(chart.encoding().keySet())) {
			throw new IllegalArgumentException("图表语义编码与图表类型不匹配");
		}
		for (Map.Entry<String, List<String>> entry : chart.encoding().entrySet()) {
			List<String> fields = entry.getValue();
			if (fields == null || fields.isEmpty() || new HashSet<>(fields).size() != fields.size()
					|| fields.stream().anyMatch(field -> field == null || !dimensionKeys.contains(field))) {
				throw new IllegalArgumentException("图表语义编码引用了非法维度");
			}
			validateChannelCardinality(chart.type(), entry.getKey(), fields.size());
		}
	}

	/**
	 * 校验图表数据行只包含声明字段且值类型与维度一致。
	 *
	 * @param chart 图表协议
	 * @param dimensions 维度定义
	 */
	private void validateRows(ChartVO.ChartSpec chart, Map<String, ChartVO.Dimension> dimensions) {
		String boxplotOutlierField = chart.type() == ChartType.BOXPLOT
			? chart.encoding().get("outlier").get(0) : null;
		for (Map<String, Object> row : chart.dataset().rows()) {
			if (row == null || !row.keySet().equals(dimensions.keySet())) {
				throw new IllegalArgumentException("图表数据行字段与维度定义不一致");
			}
			for (Map.Entry<String, ChartVO.Dimension> entry : dimensions.entrySet()) {
				Object value = row.get(entry.getKey());
				if (value == null) {
					if (isNullableHistoricalValue(chart, entry.getKey())) {
						continue;
					}
					throw new IllegalArgumentException("图表数据行字段不能为空");
				}
				if (entry.getKey().equals(boxplotOutlierField)) {
					validateBoxplotOutliers(value);
				}
				else if (!matchesDataType(value, entry.getValue().dataType())) {
					throw new IllegalArgumentException("图表数据行字段类型与维度定义不一致");
				}
			}
		}
	}

	/**
	 * 校验图表来源摘要不为空且不超过多来源上限。
	 *
	 * @param source 图表来源摘要
	 */
	private void validateSource(ChartVO.ChartSource source) {
		if (source == null || source.toolNames() == null || source.toolNames().isEmpty()
				|| source.toolNames().size() > 3
				|| new HashSet<>(source.toolNames()).size() != source.toolNames().size()) {
			throw new IllegalArgumentException("图表来源摘要不合法");
		}
		for (String toolName : source.toolNames()) {
			if (toolName == null || toolName.isBlank() || toolName.length() > 120) {
				throw new IllegalArgumentException("图表来源Tool名称不合法");
			}
		}
	}

	/**
	 * 校验特殊多字段语义通道和普通单字段通道的字段数量。
	 *
	 * @param type 图表类型
	 * @param channel 语义通道
	 * @param size 字段数量
	 */
	private void validateChannelCardinality(ChartType type, String channel, int size) {
		if (type == ChartType.RADAR && Set.of("indicator", "indicatorMax").contains(channel)) {
			if (size < 3) {
				throw new IllegalArgumentException("雷达图至少需要三个指标字段");
			}
			return;
		}
		if (type == ChartType.PARALLEL && "parallel".equals(channel)) {
			if (size < 3) {
				throw new IllegalArgumentException("平行坐标图至少需要三个数值字段");
			}
			return;
		}
		if (type == ChartType.BOXPLOT && "value".equals(channel)) {
			if (size != 5) {
				throw new IllegalArgumentException("箱线图必须包含五数概括字段");
			}
			return;
		}
		if (size != 1) {
			throw new IllegalArgumentException("图表语义通道只能引用一个字段");
		}
	}

	/**
	 * 判断字段值是否符合维度声明的数据类型。
	 *
	 * @param value 字段值
	 * @param dataType 协议数据类型
	 * @return 是否匹配
	 */
	private boolean matchesDataType(Object value, String dataType) {
		return switch (dataType) {
			case "number" -> value instanceof Number;
			case "boolean" -> value instanceof Boolean;
			case "date", "datetime" -> matchesTemporalDataType(value, dataType);
			case "string" -> value instanceof String || value instanceof Character;
			default -> false;
		};
	}

	/**
	 * 校验箱线图异常值字段为纯数值列表。
	 *
	 * @param value 异常值字段
	 */
	private void validateBoxplotOutliers(Object value) {
		if (!(value instanceof List<?> values) || values.stream().anyMatch(item -> !(item instanceof Number))) {
			throw new IllegalArgumentException("箱线图异常值字段格式不合法");
		}
	}

	/**
	 * 判断历史协议字段是否允许保留业务空值。
	 *
	 * @param chart 图表协议
	 * @param field 维度字段
	 * @return 是否允许为空
	 */
	private boolean isNullableHistoricalValue(ChartVO.ChartSpec chart, String field) {
		return chart.type() == ChartType.HEATMAP
			&& chart.encoding().get("value").contains(field);
	}

	/**
	 * 校验历史雷达图指标和值上界一一对应且范围有效。
	 *
	 * @param chart 图表协议
	 * @throws IllegalArgumentException 雷达指标或上界不合法时抛出
	 */
	private void validateRadarSemantics(ChartVO.ChartSpec chart) {
		if (chart.type() != ChartType.RADAR) {
			return;
		}
		List<String> indicatorFields = chart.encoding().get("indicator");
		List<String> maximumFields = chart.encoding().get("indicatorMax");
		if (indicatorFields.size() != maximumFields.size()) {
			throw new IllegalArgumentException("雷达图指标与上界数量不一致");
		}
		for (Map<String, Object> row : chart.dataset().rows()) {
			for (int index = 0; index < indicatorFields.size(); index++) {
				BigDecimal indicator = toDecimal(row.get(indicatorFields.get(index)));
				BigDecimal maximum = toDecimal(row.get(maximumFields.get(index)));
				if (indicator.signum() < 0 || maximum.signum() <= 0 || maximum.compareTo(indicator) < 0) {
					throw new IllegalArgumentException("雷达图指标上界不合法");
				}
			}
		}
	}

	/**
	 * 将协议数值转换为保持精度的 BigDecimal。
	 *
	 * @param value 协议数值
	 * @return BigDecimal 数值
	 */
	private BigDecimal toDecimal(Object value) {
		return new BigDecimal(((Number) value).toString());
	}

	/**
	 * 校验历史协议语义通道引用的维度类型符合图表业务含义。
	 *
	 * @param chart 图表协议
	 * @param dimensions 已声明维度
	 * @throws IllegalArgumentException 语义通道字段类型不匹配时抛出
	 */
	private void validateSemanticChannelTypes(ChartVO.ChartSpec chart,
			Map<String, ChartVO.Dimension> dimensions) {
		for (String channel : NUMERIC_CHANNELS.getOrDefault(chart.type(), Set.of())) {
			List<String> fields = chart.encoding().get(channel);
			if (fields != null && fields.stream()
					.anyMatch(field -> !"number".equals(dimensions.get(field).dataType()))) {
				throw new IllegalArgumentException("图表语义通道字段类型不匹配");
			}
		}
		if (chart.type() != ChartType.GANTT) {
			return;
		}
		// 甘特图开始和结束时间必须声明为可解析的日期类维度。
		for (String channel : List.of("start", "end")) {
			String dataType = dimensions.get(chart.encoding().get(channel).get(0)).dataType();
			if (!Set.of("date", "datetime").contains(dataType)) {
				throw new IllegalArgumentException("图表语义通道字段类型不匹配");
			}
		}
	}

	/**
	 * 判断日期字段值是否为声明类型支持的真实日期。
	 *
	 * @param value 日期字段值
	 * @param dataType 日期或日期时间类型
	 * @return 是否为合法日期值
	 */
	private boolean matchesTemporalDataType(Object value, String dataType) {
		if (value instanceof TemporalAccessor) {
			return true;
		}
		if (!(value instanceof String text) || text.isBlank()) {
			return false;
		}
		try {
			if ("date".equals(dataType)) {
				LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE);
			}
			else {
				DateTimeFormatter.ISO_DATE_TIME.parse(text);
			}
			return true;
		}
		catch (DateTimeParseException ex) {
			return false;
		}
	}

}
