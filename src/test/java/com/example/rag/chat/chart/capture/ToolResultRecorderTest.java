package com.example.rag.chat.chart.capture;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.alibaba.fastjson2.JSON;
import com.example.rag.chat.chart.model.BusinessToolResult;
import com.example.rag.vo.ChartVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 业务 Tool 结果记录器测试。
 */
class ToolResultRecorderTest {

	/**
	 * 验证数组、rows 包装对象和单对象结果均可安全解析。
	 */
	@Test
	public void shouldParseSupportedResultShapes() {
		ToolResultRecorder recorder = new ToolResultRecorder();

		boolean arrayAccepted = recorder.capture("t1", "ENT001", "c1",
			"array_tool", "code", "[{\"id\":1},{\"id\":2}]");
		boolean rowsAccepted = recorder.capture("t1", "ENT001", "c1",
			"rows_tool", "database", "{\"rows\":[{\"id\":3}]}");
		boolean objectAccepted = recorder.capture("t1", "ENT001", "c1",
			"object_tool", "database", "{\"id\":4,\"remark\":null}");

		assertThat(arrayAccepted).isTrue();
		assertThat(rowsAccepted).isTrue();
		assertThat(objectAccepted).isTrue();
		assertThat(recorder.getResults("t1", "ENT001", "c1"))
			.extracting(BusinessToolResult::sequence)
			.containsExactly(1, 2, 3);
	}

	/**
	 * 验证标量、非法 JSON、超长字符串、过深嵌套和超量数据均不会进入图表上下文。
	 */
	@Test
	public void shouldRejectUnsupportedOrOversizedResults() {
		ToolResultRecorder recorder = new ToolResultRecorder();
		List<Map<String, Object>> tooManyRows = java.util.stream.IntStream
			.rangeClosed(0, ToolResultRecorder.MAX_ROWS_PER_TOOL)
			.mapToObj(index -> Map.<String, Object>of("id", index))
			.toList();

		assertThat(recorder.capture("t1", "ENT001", "c1",
			"scalar", "code", "123")).isFalse();
		assertThat(recorder.capture("t1", "ENT001", "c1",
			"invalid", "code", "{invalid")).isFalse();
		assertThat(recorder.capture("t1", "ENT001", "c1",
			"long_text", "code", JSON.toJSONString(Map.of(
				"text", "字".repeat(ToolResultRecorder.MAX_STRING_LENGTH + 1))))).isFalse();
		assertThat(recorder.capture("t1", "ENT001", "c1",
			"deep", "code", deeplyNestedJson())).isFalse();
		assertThat(recorder.capture("t1", "ENT001", "c1",
			"many", "code", JSON.toJSONString(tooManyRows))).isFalse();
		assertThat(recorder.contextCount()).isZero();
	}

	/**
	 * 验证每轮只保留前八次成功业务 Tool 结果。
	 */
	@Test
	public void shouldLimitBusinessToolCallsPerTrace() {
		ToolResultRecorder recorder = new ToolResultRecorder();
		for (int index = 1; index <= ToolResultRecorder.MAX_CALLS_PER_TRACE; index++) {
			assertThat(recorder.capture("t1", "ENT001", "c1",
				"tool_" + index, "database", "{\"id\":" + index + "}")).isTrue();
		}

		boolean ninthAccepted = recorder.capture("t1", "ENT001", "c1",
			"tool_9", "database", "{\"id\":9}");

		assertThat(ninthAccepted).isFalse();
		assertThat(recorder.getResults("t1", "ENT001", "c1"))
			.hasSize(ToolResultRecorder.MAX_CALLS_PER_TRACE);
	}

	/**
	 * 验证租户或会话边界不匹配时无法读取结果和写入图表。
	 */
	@Test
	public void shouldEnforceTenantAndConversationBoundary() {
		ToolResultRecorder recorder = new ToolResultRecorder();
		recorder.capture("t1", "ENT001", "c1", "sales", "database", "{\"value\":10}");

		boolean accepted = recorder.acceptChart("t1", "ENT002", "c1", validChart("chart-1"));

		assertThat(accepted).isFalse();
		assertThat(recorder.getResults("t1", "ENT002", "c1")).isEmpty();
		assertThat(recorder.getResults("t1", "ENT001", "c2")).isEmpty();
	}

	/**
	 * 验证同一轮只接受第一个有效图表。
	 */
	@Test
	public void shouldKeepFirstAcceptedChart() {
		ToolResultRecorder recorder = new ToolResultRecorder();
		recorder.capture("t1", "ENT001", "c1", "sales", "database", "{\"value\":10}");

		boolean firstAccepted = recorder.acceptChart("t1", "ENT001", "c1", validChart("chart-1"));
		boolean secondAccepted = recorder.acceptChart("t1", "ENT001", "c1", validChart("chart-2"));

		assertThat(firstAccepted).isTrue();
		assertThat(secondAccepted).isFalse();
		assertThat(recorder.getChart("t1", "ENT001", "c1").chartId()).isEqualTo("chart-1");
	}

	/**
	 * 验证不同 trace 并发捕获时不会串联业务数据。
	 */
	@Test
	public void shouldIsolateConcurrentTraces() throws Exception {
		ToolResultRecorder recorder = new ToolResultRecorder();
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		try {
			executor.submit(() -> captureAfterStart(recorder, "t1", "c1", ready, start));
			executor.submit(() -> captureAfterStart(recorder, "t2", "c2", ready, start));
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			executor.shutdown();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
		finally {
			executor.shutdownNow();
		}

		assertThat(recorder.getResults("t1", "ENT001", "c1"))
			.extracting(BusinessToolResult::toolName).containsExactly("tool_t1");
		assertThat(recorder.getResults("t2", "ENT001", "c2"))
			.extracting(BusinessToolResult::toolName).containsExactly("tool_t2");
	}

	/**
	 * 验证成功、异常或取消路径均可使用同一清理操作释放上下文。
	 */
	@Test
	public void shouldClearContextForEveryTerminalPath() {
		ToolResultRecorder recorder = new ToolResultRecorder();
		for (String traceId : List.of("success", "error", "cancel")) {
			recorder.capture(traceId, "ENT001", "c1", "sales", "database", "{\"value\":10}");
			recorder.clear(traceId);
		}

		assertThat(recorder.contextCount()).isZero();
	}

	/**
	 * 并发测试任务等待统一开始后写入独立 trace。
	 *
	 * @param recorder 结果记录器
	 * @param traceId  链路 ID
	 * @param cid      会话 ID
	 * @param ready    就绪信号
	 * @param start    开始信号
	 */
	private void captureAfterStart(ToolResultRecorder recorder, String traceId, String cid,
			CountDownLatch ready, CountDownLatch start) {
		try {
			ready.countDown();
			start.await(5, TimeUnit.SECONDS);
			recorder.capture(traceId, "ENT001", cid, "tool_" + traceId, "database", "{\"value\":10}");
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * 构造超过允许深度的 JSON。
	 *
	 * @return 深层嵌套 JSON
	 */
	private String deeplyNestedJson() {
		String json = "\"value\"";
		for (int depth = 0; depth <= ToolResultRecorder.MAX_NESTING_DEPTH; depth++) {
			json = "{\"level\":" + json + "}";
		}
		return json;
	}

	/**
	 * 构造测试图表。
	 *
	 * @param chartId 图表 ID
	 * @return 测试图表
	 */
	private ChartVO.ChartSpec validChart(String chartId) {
		return new ChartVO.ChartSpec(ChartVO.SCHEMA_VERSION, chartId, ChartVO.ChartType.GAUGE, "指标", null,
			new ChartVO.Dataset(
				List.of(new ChartVO.Dimension("value", "值", "number", null)),
				List.of(Map.of("value", 10))),
			Map.of("value", List.of("value")), null,
			new ChartVO.ChartSource(List.of("sales")));
	}

	/**
	 * 验证原始字节数、对象宽度、集合宽度和总节点数均受资源上限保护。
	 */
	@Test
	public void shouldRejectResultsExceedingStructuralBudgets() {
		ToolResultRecorder recorder = new ToolResultRecorder();
		Map<String, Object> wideObject = new LinkedHashMap<>();
		for (int index = 0; index <= ToolResultRecorder.MAX_FIELDS_PER_OBJECT; index++) {
			wideObject.put("field" + index, index);
		}
		List<Integer> wideCollection = java.util.stream.IntStream
			.rangeClosed(0, ToolResultRecorder.MAX_COLLECTION_ITEMS)
			.boxed()
			.toList();
		Map<String, Object> excessiveNodes = new LinkedHashMap<>();
		for (int fieldIndex = 0; fieldIndex < 26; fieldIndex++) {
			List<Integer> values = new ArrayList<>();
			for (int itemIndex = 0; itemIndex < ToolResultRecorder.MAX_COLLECTION_ITEMS; itemIndex++) {
				values.add(itemIndex);
			}
			excessiveNodes.put("list" + fieldIndex, values);
		}

		assertThat(recorder.capture("raw", "ENT001", "c1", "raw", "database",
			"{\"value\":\"" + "a".repeat(ToolResultRecorder.MAX_RESULT_JSON_BYTES) + "\"}"))
			.isFalse();
		assertThat(recorder.capture("wide-map", "ENT001", "c1", "wide_map", "database",
			JSON.toJSONString(wideObject))).isFalse();
		assertThat(recorder.capture("wide-list", "ENT001", "c1", "wide_list", "database",
			JSON.toJSONString(Map.of("items", wideCollection)))).isFalse();
		assertThat(recorder.capture("nodes", "ENT001", "c1", "nodes", "database",
			JSON.toJSONString(excessiveNodes))).isFalse();
		assertThat(recorder.contextCount()).isZero();
	}

	/**
	 * 验证同一会话的不同问答轮次可以分别接受一个图表。
	 */
	@Test
	public void shouldAllowOneChartPerTurnWithinSameConversation() {
		ToolResultRecorder recorder = new ToolResultRecorder();
		recorder.capture("turn-1", "ENT001", "c1", "sales", "database", "{\"value\":10}");
		recorder.capture("turn-2", "ENT001", "c1", "sales", "database", "{\"value\":20}");

		boolean firstTurnAccepted = recorder.acceptChart(
			"turn-1", "ENT001", "c1", validChart("chart-1"));
		boolean secondTurnAccepted = recorder.acceptChart(
			"turn-2", "ENT001", "c1", validChart("chart-2"));

		assertThat(firstTurnAccepted).isTrue();
		assertThat(secondTurnAccepted).isTrue();
		assertThat(recorder.getChart("turn-1", "ENT001", "c1").chartId()).isEqualTo("chart-1");
		assertThat(recorder.getChart("turn-2", "ENT001", "c1").chartId()).isEqualTo("chart-2");
	}

	/**
	 * 验证超出原始字节预算的空 JSON 不会被二次解析并误判为空业务结果。
	 *
	 * @param output 测试期间捕获的日志输出
	 */
	@Test
	@ExtendWith(OutputCaptureExtension.class)
	public void shouldRejectOversizedEmptyJsonBeforeParsing(CapturedOutput output) {
		ToolResultRecorder recorder = new ToolResultRecorder();
		String oversizedEmptyJson = " ".repeat(ToolResultRecorder.MAX_RESULT_JSON_BYTES + 1) + "[]";

		boolean accepted = recorder.capture(
			"oversized-empty", "ENT001", "c1", "oversized", "database", oversizedEmptyJson);

		assertThat(accepted).isFalse();
		assertThat(recorder.contextCount()).isZero();
		assertThat(output).contains("业务 Tool 结果不是受支持的结构化数据");
		assertThat(output).doesNotContain("业务 Tool 返回空结果");
	}

}
