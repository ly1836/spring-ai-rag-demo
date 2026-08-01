package com.example.rag.vo;

import java.util.List;
import java.util.Map;

import com.alibaba.fastjson2.annotation.JSONCreator;
import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 图表展示模块 VO，定义后端与前端共享的通用图表数据协议。
 */
public final class ChartVO {

	/** 当前图表协议版本。 */
	public static final String SCHEMA_VERSION = "1.0";

	private ChartVO() {
	}

	/**
	 * 后端统一图表类型，枚举编码保持与前端及历史 JSON 协议一致。
	 */
	public enum ChartType {

		/** 环形图。 */
		DONUT("donut"),

		/** 旭日图。 */
		SUNBURST("sunburst"),

		/** 条形图。 */
		BAR("bar"),

		/** 瀑布图。 */
		WATERFALL("waterfall"),

		/** 子弹图。 */
		BULLET("bullet"),

		/** 面积图。 */
		AREA("area"),

		/** 阶梯图。 */
		STEP("step"),

		/** 雷达图。 */
		RADAR("radar"),

		/** 散点图。 */
		SCATTER("scatter"),

		/** 气泡图。 */
		BUBBLE("bubble"),

		/** 直方图。 */
		HISTOGRAM("histogram"),

		/** 箱线图。 */
		BOXPLOT("boxplot"),

		/** 热力图。 */
		HEATMAP("heatmap"),

		/** 桑基图。 */
		SANKEY("sankey"),

		/** 矩形树图。 */
		TREEMAP("treemap"),

		/** 甘特图。 */
		GANTT("gantt"),

		/** 漏斗图。 */
		FUNNEL("funnel"),

		/** 词云图。 */
		WORD_CLOUD("word-cloud"),

		/** 仪表盘图。 */
		GAUGE("gauge"),

		/** 水位图。 */
		LIQUID_FILL("liquid-fill"),

		/** 平行坐标图。 */
		PARALLEL("parallel"),

		/** 折线图。 */
		LINE("line"),

		/** 饼图。 */
		PIE("pie");

		/** 对外 JSON 协议编码。 */
		private final String code;

		/**
		 * 创建图表类型。
		 *
		 * @param code 对外 JSON 协议编码
		 */
		private ChartType(String code) {
			this.code = code;
		}

		/**
		 * 返回与前端及历史数据兼容的协议编码。
		 *
		 * @return 图表类型协议编码
		 */
		@JsonValue
		@JSONField(value = true)
		public String getCode() {
			return code;
		}

		/**
		 * 根据前端或历史 JSON 协议编码解析图表类型。
		 *
		 * @param code 图表类型协议编码
		 * @return 图表类型，编码为空时返回 null
		 * @throws IllegalArgumentException 编码不受支持时抛出
		 */
		@JsonCreator
		@JSONCreator
		public static ChartType fromCode(String code) {
			if (code == null) {
				return null;
			}
			for (ChartType type : values()) {
				if (type.code.equals(code)) {
					return type;
				}
			}
			throw new IllegalArgumentException("不支持的图表类型: " + code);
		}

		/**
		 * 返回全部图表类型协议编码，供 LLM Tool Schema 复用。
		 *
		 * @return 全部图表类型协议编码
		 */
		public static List<String> codes() {
			return java.util.Arrays.stream(values())
				.map(ChartType::getCode)
				.toList();
		}

	}

	/**
	 * 前后端通用图表定义。
	 *
	 * @param schemaVersion 图表协议版本
	 * @param chartId       图表唯一标识
	 * @param type          图表类型
	 * @param title         图表标题
	 * @param subtitle      图表副标题
	 * @param dataset       图表数据集
	 * @param encoding      语义通道与维度键映射
	 * @param options       安全展示选项
	 * @param source        数据来源摘要
	 */
	public record ChartSpec(String schemaVersion, String chartId, ChartType type, String title, String subtitle,
			Dataset dataset, Map<String, List<String>> encoding, ChartOptions options, ChartSource source) {
	}

	/**
	 * 图表数据集。
	 *
	 * @param dimensions 维度定义
	 * @param rows       数据行
	 */
	public record Dataset(List<Dimension> dimensions, List<Map<String, Object>> rows) {
	}

	/**
	 * 图表数据维度。
	 *
	 * @param key      稳定字段键
	 * @param label    展示名称
	 * @param dataType 数据类型
	 * @param unit     展示单位
	 */
	public record Dimension(String key, String label, String dataType, String unit) {
	}

	/**
	 * 图表安全展示选项。
	 *
	 * @param orientation 图表方向
	 * @param stacked     是否堆叠
	 * @param smooth      是否平滑
	 * @param step        阶梯方式
	 * @param binCount    分箱数量
	 * @param min         最小值
	 * @param max         最大值
	 * @param unit        展示单位
	 * @param sort        排序方向
	 * @param showLabel   是否展示标签
	 */
	public record ChartOptions(String orientation, Boolean stacked, Boolean smooth, String step,
			Integer binCount, Double min, Double max, String unit, String sort, Boolean showLabel) {
	}

	/**
	 * 图表数据来源摘要。
	 *
	 * @param toolNames 来源业务 Tool 名称
	 */
	public record ChartSource(List<String> toolNames) {
	}

}
