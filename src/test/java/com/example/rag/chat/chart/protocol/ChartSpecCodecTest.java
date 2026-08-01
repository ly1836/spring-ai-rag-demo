package com.example.rag.chat.chart.protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.example.rag.vo.ChartVO;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 图表协议编解码器测试。
 */
class ChartSpecCodecTest {

	private final ChartSpecCodec codec = new ChartSpecCodec();

	/**
	 * 验证有效图表可以完成 JSON 往返转换。
	 */
	@Test
	public void shouldRoundTripValidChartSpec() {
		ChartVO.ChartSpec chart = validChart();

		String json = codec.encode(chart);
		ChartVO.ChartSpec decoded = codec.decode(json);

		assertThat(decoded).isEqualTo(chart);
		assertThat(json).contains("\"type\":\"bar\"");
	}

	/**
	 * 验证未知协议版本和图表类型会被拒绝。
	 */
	@Test
	public void shouldRejectUnknownVersionAndType() {
		ChartVO.ChartSpec chart = validChart();
		ChartVO.ChartSpec invalidVersion = new ChartVO.ChartSpec(
			"2.0", chart.chartId(), chart.type(), chart.title(), chart.subtitle(),
			chart.dataset(), chart.encoding(), chart.options(), chart.source());
		String invalidTypeJson = codec.encode(chart)
			.replace("\"type\":\"bar\"", "\"type\":\"unknown\"");

		assertThatThrownBy(() -> codec.encode(invalidVersion))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("不支持的图表协议版本");
		assertThatThrownBy(() -> codec.decode(invalidTypeJson))
			.isInstanceOf(IllegalArgumentException.class);
	}

	/**
	 * 验证超过最大数据行数的图表会被拒绝。
	 */
	@Test
	public void shouldRejectTooManyRows() {
		ChartVO.ChartSpec chart = validChart();
		List<Map<String, Object>> rows = new ArrayList<>();
		for (int index = 0; index <= ChartSpecCodec.MAX_ROWS; index++) {
			rows.add(Map.of("name", "产品" + index, "value", index));
		}
		ChartVO.ChartSpec oversized = new ChartVO.ChartSpec(
			chart.schemaVersion(), chart.chartId(), chart.type(), chart.title(), chart.subtitle(),
			new ChartVO.Dataset(chart.dataset().dimensions(), rows),
			chart.encoding(), chart.options(), chart.source());

		assertThatThrownBy(() -> codec.encode(oversized))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("图表数据行数超过限制");
	}

	/**
	 * 构造有效图表协议。
	 *
	 * @return 有效图表协议
	 */
	private ChartVO.ChartSpec validChart() {
		return new ChartVO.ChartSpec(
			ChartVO.SCHEMA_VERSION, "chart-1", ChartVO.ChartType.BAR, "销售额", "按产品统计",
			new ChartVO.Dataset(
				List.of(
					new ChartVO.Dimension("category", "产品", "string", null),
					new ChartVO.Dimension("value", "销售额", "number", "元")),
				List.of(Map.of("category", "产品A", "value", 100))),
			Map.of("category", List.of("category"), "value", List.of("value")),
			new ChartVO.ChartOptions("vertical", false, false, null,
				null, null, null, "元", null, true),
			new ChartVO.ChartSource(List.of("query_sales")));
	}

	/**
	 * 验证历史图表缺少必需通道或引用未声明维度时会被拒绝。
	 */
	@Test
	public void shouldRejectInvalidHistoricalEncoding() {
		ChartVO.ChartSpec chart = validChart();
		ChartVO.ChartSpec missingChannel = new ChartVO.ChartSpec(
			chart.schemaVersion(), chart.chartId(), chart.type(), chart.title(), chart.subtitle(),
			chart.dataset(), Map.of("category", List.of("category")), chart.options(), chart.source());
		ChartVO.ChartSpec unknownDimension = new ChartVO.ChartSpec(
			chart.schemaVersion(), chart.chartId(), chart.type(), chart.title(), chart.subtitle(),
			chart.dataset(), Map.of("category", List.of("category"), "value", List.of("missing")),
			chart.options(), chart.source());

		assertThatThrownBy(() -> codec.encode(missingChannel))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("图表语义编码与图表类型不匹配");
		assertThatThrownBy(() -> codec.encode(unknownDimension))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("图表语义编码引用了非法维度");
	}

	/**
	 * 验证历史图表数据行字段和数据类型必须与维度定义一致。
	 */
	@Test
	public void shouldRejectInvalidHistoricalRows() {
		ChartVO.ChartSpec chart = validChart();
		ChartVO.ChartSpec extraField = new ChartVO.ChartSpec(
			chart.schemaVersion(), chart.chartId(), chart.type(), chart.title(), chart.subtitle(),
			new ChartVO.Dataset(chart.dataset().dimensions(),
				List.of(Map.of("category", "产品A", "value", 100, "hidden", "内部值"))),
			chart.encoding(), chart.options(), chart.source());
		ChartVO.ChartSpec wrongType = new ChartVO.ChartSpec(
			chart.schemaVersion(), chart.chartId(), chart.type(), chart.title(), chart.subtitle(),
			new ChartVO.Dataset(chart.dataset().dimensions(),
				List.of(Map.of("category", "产品A", "value", "100"))),
			chart.encoding(), chart.options(), chart.source());

		assertThatThrownBy(() -> codec.encode(extraField))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("图表数据行字段与维度定义不一致");
		assertThatThrownBy(() -> codec.encode(wrongType))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("图表数据行字段类型与维度定义不一致");
	}

	/**
	 * 验证历史图表的展示选项和值空缺必须通过严格白名单校验。
	 */
	@Test
	public void shouldRejectInvalidHistoricalOptionsAndUnexpectedNullValues() {
		ChartVO.ChartSpec chart = validChart();
		ChartVO.ChartSpec invalidOptions = new ChartVO.ChartSpec(
			chart.schemaVersion(), chart.chartId(), chart.type(), chart.title(), chart.subtitle(),
			chart.dataset(), chart.encoding(),
			new ChartVO.ChartOptions("diagonal", false, false, null,
				null, null, null, "元", null, true), chart.source());
		Map<String, Object> nullValueRow = new LinkedHashMap<>();
		nullValueRow.put("category", "产品A");
		nullValueRow.put("value", null);
		ChartVO.ChartSpec nullValue = new ChartVO.ChartSpec(
			chart.schemaVersion(), chart.chartId(), chart.type(), chart.title(), chart.subtitle(),
			new ChartVO.Dataset(chart.dataset().dimensions(), List.of(nullValueRow)),
			chart.encoding(), chart.options(), chart.source());

		assertThatThrownBy(() -> codec.encode(invalidOptions))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("图表方向不受支持");
		assertThatThrownBy(() -> codec.encode(nullValue))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("图表数据行字段不能为空");
	}

	/**
	 * 验证历史雷达图指标、上界必须一一对应且上界不能小于指标值。
	 */
	@Test
	public void shouldRejectInvalidHistoricalRadarBounds() {
		Map<String, Object> mismatchedRow = new LinkedHashMap<>();
		mismatchedRow.put("name", "项目A");
		mismatchedRow.put("p1", 1);
		mismatchedRow.put("p2", 2);
		mismatchedRow.put("p3", 3);
		mismatchedRow.put("p4", 4);
		mismatchedRow.put("p1Max", 5);
		mismatchedRow.put("p2Max", 5);
		mismatchedRow.put("p3Max", 5);
		ChartVO.ChartSpec mismatched = radarChart(
			mismatchedRow, List.of("p1", "p2", "p3", "p4"),
			List.of("p1Max", "p2Max", "p3Max"));

		Map<String, Object> tooSmallRow = new LinkedHashMap<>();
		tooSmallRow.put("name", "项目A");
		tooSmallRow.put("p1", 6);
		tooSmallRow.put("p2", 2);
		tooSmallRow.put("p3", 3);
		tooSmallRow.put("p1Max", 5);
		tooSmallRow.put("p2Max", 5);
		tooSmallRow.put("p3Max", 5);
		ChartVO.ChartSpec tooSmall = radarChart(
			tooSmallRow, List.of("p1", "p2", "p3"),
			List.of("p1Max", "p2Max", "p3Max"));

		assertThatThrownBy(() -> codec.encode(mismatched))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("雷达图指标与上界数量不一致");
		assertThatThrownBy(() -> codec.encode(tooSmall))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("雷达图指标上界不合法");
	}

	/**
	 * 验证历史热力图仍允许数值通道保留业务空值。
	 */
	@Test
	public void shouldAllowNullableHistoricalHeatmapValues() {
		Map<String, Object> emptyValue = new LinkedHashMap<>();
		emptyValue.put("month", "一月");
		emptyValue.put("region", "华东");
		emptyValue.put("sales", null);
		ChartVO.ChartSpec chart = new ChartVO.ChartSpec(
			ChartVO.SCHEMA_VERSION, "heatmap-history", ChartVO.ChartType.HEATMAP, "历史热力图", null,
			new ChartVO.Dataset(
				List.of(
					new ChartVO.Dimension("month", "月份", "string", null),
					new ChartVO.Dimension("region", "区域", "string", null),
					new ChartVO.Dimension("sales", "销售额", "number", "元")),
				List.of(emptyValue, Map.of("month", "二月", "region", "华东", "sales", 5))),
			Map.of("x", List.of("month"), "y", List.of("region"), "value", List.of("sales")),
			null, new ChartVO.ChartSource(List.of("query_heatmap")));

		String historicalJson = JSON.toJSONString(chart, JSONWriter.Feature.WriteNulls);
		assertThat(codec.decode(historicalJson)).isEqualTo(chart);
	}

	/**
	 * 构造历史雷达图协议。
	 *
	 * @param row             数据行
	 * @param indicatorFields 指标字段
	 * @param maximumFields   上界字段
	 * @return 雷达图协议
	 */
	private ChartVO.ChartSpec radarChart(Map<String, Object> row, List<String> indicatorFields,
			List<String> maximumFields) {
		List<ChartVO.Dimension> dimensions = row.keySet().stream()
			.map(key -> new ChartVO.Dimension(key, key, "name".equals(key) ? "string" : "number", null))
			.toList();
		return new ChartVO.ChartSpec(
			ChartVO.SCHEMA_VERSION, "radar-history", ChartVO.ChartType.RADAR, "历史雷达图", null,
			new ChartVO.Dataset(dimensions, List.of(row)),
			Map.of("name", List.of("name"), "indicator", indicatorFields,
				"indicatorMax", maximumFields),
			null, new ChartVO.ChartSource(List.of("query_radar")));
	}

	/**
	 * 验证历史协议的语义通道类型和日期文本必须符合真实业务含义。
	 */
	@Test
	public void shouldRejectHistoricalSemanticTypeMismatchesAndMalformedDates() {
		ChartVO.ChartSpec chart = validChart();
		ChartVO.ChartSpec stringValueBar = new ChartVO.ChartSpec(
			chart.schemaVersion(), chart.chartId(), chart.type(), chart.title(), chart.subtitle(),
			new ChartVO.Dataset(
				List.of(
					new ChartVO.Dimension("category", "产品", "string", null),
					new ChartVO.Dimension("value", "销售额", "string", "元")),
				List.of(Map.of("category", "产品A", "value", "100"))),
			chart.encoding(), chart.options(), chart.source());
		ChartVO.ChartSpec malformedGantt = new ChartVO.ChartSpec(
			ChartVO.SCHEMA_VERSION, "gantt-history", ChartVO.ChartType.GANTT, "历史甘特图", null,
			new ChartVO.Dataset(
				List.of(
					new ChartVO.Dimension("category", "任务", "string", null),
					new ChartVO.Dimension("start", "开始时间", "datetime", null),
					new ChartVO.Dimension("end", "结束时间", "datetime", null)),
				List.of(Map.of("category", "任务A", "start", "不是日期", "end", "仍不是日期"))),
			Map.of("category", List.of("category"), "start", List.of("start"), "end", List.of("end")),
			null, new ChartVO.ChartSource(List.of("query_gantt")));

		assertThatThrownBy(() -> codec.encode(stringValueBar))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("图表语义通道字段类型不匹配");
		assertThatThrownBy(() -> codec.encode(malformedGantt))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("图表数据行字段类型与维度定义不一致");
	}

}
