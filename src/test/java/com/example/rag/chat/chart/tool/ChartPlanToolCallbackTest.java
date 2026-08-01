package com.example.rag.chat.chart.tool;

import java.util.List;
import java.util.Map;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.example.rag.chat.chart.capture.ToolResultRecorder;
import com.example.rag.chat.chart.compile.ChartCompiler;
import com.example.rag.chat.chart.compile.ChartPlanFactory;
import com.example.rag.chat.chart.compile.ChartPlanValidator;
import com.example.rag.chat.chart.protocol.ChartSpecCodec;
import com.example.rag.vo.ChartVO;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.model.ToolContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LLM 图表选择内部 Tool 测试。
 */
class ChartPlanToolCallbackTest {

	/**
	 * 验证模型侧 Schema 只开放图表类型和标题。
	 */
	@Test
	public void shouldExposeTypeAndTitleOnlySchema() {
		ChartPlanToolCallback callback = buildCallback(new ToolResultRecorder());

		JSONObject schema = JSON.parseObject(callback.getToolDefinition().inputSchema());
		JSONObject properties = schema.getJSONObject("properties");
		String description = callback.getToolDefinition().description();

		assertThat(callback.getToolDefinition().name()).isEqualTo(ChartPlanToolCallback.TOOL_NAME);
		assertThat(properties.keySet()).containsExactlyInAnyOrder("type", "title");
		assertThat(schema.getJSONArray("required")).containsExactly("type", "title");
		assertThat(properties.getJSONObject("type").getJSONArray("enum"))
			.contains("donut", "parallel", "bar");
		assertThat(callback.getToolDefinition().inputSchema())
			.doesNotContain("sourceToolNames", "bindings", "transform", "options", "echartsOption");
		assertThat(description)
			.contains("【用途】")
			.contains("【调用条件】")
			.contains("【填写规则】")
			.contains("【图表选择】")
			.contains("包含数值字段或可按状态、类型等分类计数")
			.contains("即使最终回答使用 Markdown 表格也不能省略")
			.contains("只填写 type 和 title")
			.contains("字段绑定、数据转换和安全展示选项由后端自动生成")
			.doesNotContain("sourceCallIndexes");
	}

	/**
	 * 验证后端根据标题和真实销售数据自动选择类别、数值字段及安全选项。
	 */
	@Test
	public void shouldBuildChartFromCapturedBusinessRows() {
		ToolResultRecorder recorder = new ToolResultRecorder();
		recorder.capture("t1", "ENT001", "c1", "query_sales", "database", """
			[{"product_name":"电源模块C型","qty":200,"total_amount":30000},
			 {"product_name":"电源模块C型","qty":50,"total_amount":7500},
			 {"product_name":"传感器模组B型","qty":350,"total_amount":52500},
			 {"product_name":"智能控制器A型","qty":270,"total_amount":135000}]
			""");
		ChartPlanToolCallback callback = buildCallback(recorder);

		JSONObject response = JSON.parseObject(callback.call(selection(
			ChartVO.ChartType.BAR, "最近一年各产品销售数量对比"), context("t1", "ENT001", "c1")));
		ChartVO.ChartSpec chart = recorder.getChart("t1", "ENT001", "c1");

		assertThat(response.getBooleanValue("accepted")).isTrue();
		assertThat(chart).isNotNull();
		assertThat(chart.type()).isEqualTo(ChartVO.ChartType.BAR);
		assertThat(chart.title()).isEqualTo("最近一年各产品销售数量对比");
		assertThat(chart.encoding().get("category")).containsExactly("product_name");
		assertThat(chart.encoding().get("value")).containsExactly("qty");
		assertThat(chart.dataset().rows()).hasSize(3);
		assertThat(chart.dataset().rows()).anySatisfy(row -> {
			assertThat(row.get("product_name")).isEqualTo("电源模块C型");
			assertThat(row.get("qty").toString()).isEqualTo("250");
		});
	}

	/**
	 * 验证第一个合法图表生效且重复选择不能覆盖。
	 */
	@Test
	public void shouldKeepFirstValidChartOnly() {
		ToolResultRecorder recorder = new ToolResultRecorder();
		recorder.capture("t1", "ENT001", "c1", "query_sales", "database",
			"[{\"category\":\"A\",\"value\":10}]");
		ChartPlanToolCallback callback = buildCallback(recorder);
		ToolContext context = context("t1", "ENT001", "c1");

		JSONObject first = JSON.parseObject(callback.call(selection(ChartVO.ChartType.BAR, "首个图表"), context));
		JSONObject second = JSON.parseObject(callback.call(selection(ChartVO.ChartType.PIE, "第二个图表"), context));

		assertThat(first.getBooleanValue("accepted")).isTrue();
		assertThat(second.getBooleanValue("accepted")).isFalse();
		assertThat(recorder.getChart("t1", "ENT001", "c1").title()).isEqualTo("首个图表");
	}

	/**
	 * 验证后端会尝试全部已捕获结果，而不是因最近一份数据不兼容直接降级。
	 */
	@Test
	public void shouldTryOtherCapturedResultsBeforeDegrading() {
		ToolResultRecorder recorder = new ToolResultRecorder();
		recorder.capture("t1", "ENT001", "c1", "query_coordinates", "database",
			"[{\"x_value\":1,\"y_value\":2},{\"x_value\":3,\"y_value\":4}]");
		recorder.capture("t1", "ENT001", "c1", "query_labels", "database",
			"[{\"category\":\"A\",\"value\":10}]");
		ChartPlanToolCallback callback = buildCallback(recorder);

		JSONObject response = JSON.parseObject(callback.call(selection(
			ChartVO.ChartType.SCATTER, "坐标关系"), context("t1", "ENT001", "c1")));
		ChartVO.ChartSpec chart = recorder.getChart("t1", "ENT001", "c1");

		assertThat(response.getBooleanValue("accepted")).isTrue();
		assertThat(chart.source().toolNames()).containsExactly("query_coordinates");
		assertThat(chart.encoding().get("x")).containsExactly("x_value");
		assertThat(chart.encoding().get("y")).containsExactly("y_value");
	}

	/**
	 * 验证数据确实无法满足模型所选类型时安全降级为文本。
	 */
	@Test
	public void shouldDegradeOnlyWhenSelectedTypeCannotUseCapturedData() {
		ToolResultRecorder recorder = new ToolResultRecorder();
		recorder.capture("t1", "ENT001", "c1", "query_sales", "database",
			"[{\"category\":\"A\",\"value\":10}]");
		ChartPlanToolCallback callback = buildCallback(recorder);

		JSONObject response = JSON.parseObject(callback.call(selection(
			ChartVO.ChartType.BUBBLE, "气泡关系"), context("t1", "ENT001", "c1")));

		assertThat(response.getBooleanValue("accepted")).isFalse();
		assertThat(response.getBooleanValue("retryable")).isFalse();
		assertThat(response.getString("reason")).isEqualTo("本轮业务数据无法满足所选图表类型");
		assertThat(recorder.getChart("t1", "ENT001", "c1")).isNull();
	}

	/**
	 * 验证租户或会话不匹配时图表选择会被拒绝。
	 */
	@Test
	public void shouldRejectMismatchedTraceBoundary() {
		ToolResultRecorder recorder = new ToolResultRecorder();
		recorder.capture("t1", "ENT001", "c1", "query_sales", "database",
			"[{\"category\":\"A\",\"value\":10}]");
		ChartPlanToolCallback callback = buildCallback(recorder);

		JSONObject response = JSON.parseObject(callback.call(selection(
			ChartVO.ChartType.BAR, "图表"), context("t1", "ENT002", "c1")));

		assertThat(response.getBooleanValue("accepted")).isFalse();
		assertThat(recorder.getChart("t1", "ENT001", "c1")).isNull();
	}

	/**
	 * 验证模型不能重新提交来源、绑定、转换或任意展示选项。
	 */
	@Test
	public void shouldRejectFieldsOutsideTypeAndTitle() {
		ToolResultRecorder recorder = new ToolResultRecorder();
		recorder.capture("t1", "ENT001", "c1", "query_sales", "database",
			"[{\"category\":\"A\",\"value\":10}]");
		ChartPlanToolCallback callback = buildCallback(recorder);
		JSONObject unknownSource = JSON.parseObject(selection(ChartVO.ChartType.BAR, "未知来源字段"));
		unknownSource.put("sourceToolNames", List.of("query_sales"));
		JSONObject unknownOptions = JSON.parseObject(selection(ChartVO.ChartType.BAR, "未知选项字段"));
		unknownOptions.put("options", Map.of("url", "javascript:alert(1)"));

		JSONObject sourceResponse = JSON.parseObject(callback.call(
			unknownSource.toJSONString(), context("t1", "ENT001", "c1")));
		JSONObject optionResponse = JSON.parseObject(callback.call(
			unknownOptions.toJSONString(), context("t1", "ENT001", "c1")));

		assertThat(sourceResponse.getBooleanValue("accepted")).isFalse();
		assertThat(optionResponse.getBooleanValue("accepted")).isFalse();
		assertThat(recorder.getChart("t1", "ENT001", "c1")).isNull();
	}

	/**
	 * 验证选择 Tool 在解析前拒绝超大文本和超深 JSON 结构。
	 */
	@Test
	public void shouldRejectOversizedAndDeeplyNestedInputBeforeCompilation() {
		ToolResultRecorder oversizedRecorder = new ToolResultRecorder();
		oversizedRecorder.capture("t1", "ENT001", "c1", "query_sales", "database",
			"[{\"category\":\"A\",\"value\":10}]");
		ChartPlanToolCallback oversizedCallback = buildCallback(oversizedRecorder);
		// 尾随空白不改变 JSON 语义，用于验证原始 UTF-8 字节预算在解析前生效。
		String oversizedInput = selection(ChartVO.ChartType.BAR, "超大选择") + " ".repeat(70 * 1024);

		JSONObject oversizedResponse = JSON.parseObject(oversizedCallback.call(
			oversizedInput, context("t1", "ENT001", "c1")));
		assertThat(oversizedResponse.getBooleanValue("accepted")).isFalse();
		assertThat(oversizedRecorder.getChart("t1", "ENT001", "c1")).isNull();

		ToolResultRecorder deepRecorder = new ToolResultRecorder();
		deepRecorder.capture("t2", "ENT001", "c2", "query_sales", "database",
			"[{\"category\":\"A\",\"value\":10}]");
		ChartPlanToolCallback deepCallback = buildCallback(deepRecorder);
		String nestedTitle = "[".repeat(9) + "\"图表\"" + "]".repeat(9);
		String deeplyNestedInput = "{\"type\":\"bar\",\"title\":" + nestedTitle + "}";

		JSONObject deepResponse = JSON.parseObject(deepCallback.call(
			deeplyNestedInput, context("t2", "ENT001", "c2")));
		assertThat(deepResponse.getBooleanValue("accepted")).isFalse();
		assertThat(deepRecorder.getChart("t2", "ENT001", "c2")).isNull();
	}

	/**
	 * 构造图表选择 Tool。
	 *
	 * @param recorder Tool 结果记录器
	 * @return 图表选择 Tool
	 */
	private ChartPlanToolCallback buildCallback(ToolResultRecorder recorder) {
		ChartPlanValidator validator = new ChartPlanValidator();
		return new ChartPlanToolCallback(
			new ChartCompiler(validator, new ChartSpecCodec()), recorder,
			new ChartPlanFactory(validator));
	}

	/**
	 * 构造模型只负责填写的图表选择 JSON。
	 *
	 * @param type  图表类型
	 * @param title 图表标题
	 * @return 图表选择 JSON
	 */
	private String selection(ChartVO.ChartType type, String title) {
		return JSON.toJSONString(Map.of("type", type.getCode(), "title", title));
	}

	/**
	 * 构造 Spring AI Tool 上下文。
	 *
	 * @param traceId        链路 ID
	 * @param entCode        租户编码
	 * @param conversationId 会话 ID
	 * @return Tool 上下文
	 */
	private ToolContext context(String traceId, String entCode, String conversationId) {
		return new ToolContext(Map.of(
			"traceId", traceId,
			"entCode", entCode,
			"conversationId", conversationId));
	}

	/**
	 * 验证只有文本分类字段的售后工单可由后端按真实记录数生成状态占比图。
	 */
	@Test
	public void shouldCountCategoricalTicketRowsForPieChart() {
		ToolResultRecorder recorder = new ToolResultRecorder();
		recorder.capture("t1", "ENT001", "c1", "query_after_sales", "database", """
			[{"ticket_no":"AS20260305","customer_name":"张三电子科技","product_name":"传感器模组B型","issue_type":"产品故障","priority":"高","status":"待处理","handler":null},
			 {"ticket_no":"AS20260304","customer_name":"王五贸易","product_name":"电源模块C型","issue_type":"退换货","priority":"中","status":"已解决","handler":"赵技术"},
			 {"ticket_no":"AS20260303","customer_name":"赵六科技","product_name":"电机驱动板D型","issue_type":"产品故障","priority":"高","status":"处理中","handler":"王技术"},
			 {"ticket_no":"AS20260302","customer_name":"李四机械","product_name":"智能控制器A型","issue_type":"使用咨询","priority":"中","status":"已解决","handler":"赵技术"},
			 {"ticket_no":"AS20260301","customer_name":"张三电子科技","product_name":"智能控制器A型","issue_type":"产品故障","priority":"高","status":"已解决","handler":"王技术"}]
			""");
		ChartPlanToolCallback callback = buildCallback(recorder);

		JSONObject response = JSON.parseObject(callback.call(selection(
			ChartVO.ChartType.PIE, "近期售后工单处理状态占比"), context("t1", "ENT001", "c1")));
		ChartVO.ChartSpec chart = recorder.getChart("t1", "ENT001", "c1");

		assertThat(response.getBooleanValue("accepted")).isTrue();
		assertThat(chart).isNotNull();
		assertThat(chart.encoding().get("name")).containsExactly("status");
		assertThat(chart.encoding().get("value")).containsExactly("ticket_no");
		assertThat(chart.dataset().rows()).containsExactly(
			Map.of("status", "已解决", "ticket_no", 3L),
			Map.of("status", "待处理", "ticket_no", 1L),
			Map.of("status", "处理中", "ticket_no", 1L));
	}

}
