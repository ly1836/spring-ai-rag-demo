package com.example.rag.chat.chart.model;

import java.util.List;

import com.example.rag.vo.ChartVO;

/**
 * 后端根据 LLM 选择的类型和标题自动生成的内部图表规划，不携带最终业务数值。
 *
 * @param type              图表类型
 * @param title             图表标题
 * @param sourceToolNames   来源 Tool 名称
 * @param sourceCallIndexes 来源 Tool 完成顺序
 * @param bindings          语义通道绑定
 * @param transform         数据转换
 * @param options           安全展示选项
 */
public record ChartPlan(ChartVO.ChartType type, String title, List<String> sourceToolNames,
		List<Integer> sourceCallIndexes, List<Binding> bindings, Transform transform,
		ChartVO.ChartOptions options) {

	/**
	 * 图表语义通道绑定。
	 *
	 * @param channel   语义通道
	 * @param field     来源字段
	 * @param label     展示名称
	 * @param aggregate 聚合方式
	 * @param unit      字段单位
	 */
	public record Binding(String channel, String field, String label, String aggregate, String unit) {
	}

	/**
	 * 图表数据转换。
	 *
	 * @param type          转换类型
	 * @param groupBy       分组字段
	 * @param sortBy        排序字段
	 * @param sortDirection 排序方向
	 * @param limit         最大数据行数
	 */
	public record Transform(String type, List<String> groupBy, String sortBy,
			String sortDirection, Integer limit) {
	}

}
