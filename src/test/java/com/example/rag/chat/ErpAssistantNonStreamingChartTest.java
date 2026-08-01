package com.example.rag.chat;

import java.util.List;
import java.util.Map;

import com.example.rag.billing.BillingService;
import com.example.rag.chat.chart.capture.ToolResultRecorder;
import com.example.rag.chat.chart.protocol.ChartSpecCodec;
import com.example.rag.chat.chart.selection.ChartSelectionService;
import com.example.rag.chat.dto.ChatAnswerResult;
import com.example.rag.chat.lifecycle.AssistantLifecycleService;
import com.example.rag.chat.output.AssistantAnswerSanitizer;
import com.example.rag.config.TenantContext;
import com.example.rag.conversation.ChatHistoryService;
import com.example.rag.tool.trace.ToolCallLogService;
import com.example.rag.tool.trace.ToolCallRecorder;
import com.example.rag.vo.ChartVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNotNull;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 非流式图表响应和持久化降级测试。
 */
class ErpAssistantNonStreamingChartTest {

	/**
	 * 清理测试租户上下文。
	 */
	@AfterEach
	public void tearDown() {
		TenantContext.clear();
	}

	/**
	 * 验证图表持久化成功时接口结果包含同一图表且只扣费一次。
	 */
	@Test
	public void shouldReturnPersistedChartAndBillOnce() {
		TestHarness harness = buildHarness();
		prepareChart(harness.recorder());
		when(harness.historyService().saveAssistantMessageAndUpdateStats(
			anyString(), anyString(), anyString(), anyString(),
			anyInt(), anyInt(), anyInt(), nullable(String.class), anyInt(),
			isNotNull(), anyInt(), any(), eq("success"), isNull()))
			.thenReturn("assistant-message");
		TenantContext.setEntCode("ENT001");
		TenantContext.setUserId("U001");

		ChatAnswerResult result = invokeRecordAndReturn(harness.lifecycleService());

		assertThat(result.answer()).isEqualTo("模型回答");
		assertThat(result.chart()).isNotNull();
		assertThat(result.chart().type()).isEqualTo(ChartVO.ChartType.BAR);
		verify(harness.billingService()).deductForTokenUsage(
			anyInt(), anyInt(), anyInt(), eq("test-model"), eq("c1"));
	}

	/**
	 * 验证带图表保存失败时只重试一次纯文本，不重复扣费或回填 Tool 流水。
	 */
	@Test
	public void shouldRetryWithoutChartAndFinalizeBusinessSideEffectsOnce() {
		TestHarness harness = buildHarness();
		prepareChart(harness.recorder());
		when(harness.historyService().saveAssistantMessageAndUpdateStats(
			anyString(), anyString(), anyString(), anyString(),
			anyInt(), anyInt(), anyInt(), nullable(String.class), anyInt(),
			isNotNull(), anyInt(), any(), eq("success"), isNull()))
			.thenThrow(new IllegalStateException("旧库暂不支持图表字段"));
		when(harness.historyService().saveAssistantMessageAndUpdateStats(
			anyString(), anyString(), anyString(), anyString(),
			anyInt(), anyInt(), anyInt(), nullable(String.class), anyInt(),
			isNull(), anyInt(), any(), eq("success"), isNull()))
			.thenReturn("assistant-message");
		TenantContext.setEntCode("ENT001");
		TenantContext.setUserId("U001");

		ChatAnswerResult result = invokeRecordAndReturn(harness.lifecycleService());

		assertThat(result.answer()).isEqualTo("模型回答");
		assertThat(result.chart()).isNull();
		verify(harness.historyService(), times(2)).saveAssistantMessageAndUpdateStats(
			anyString(), anyString(), anyString(), anyString(),
			anyInt(), anyInt(), anyInt(), nullable(String.class), anyInt(),
			nullable(String.class), anyInt(), any(), eq("success"), isNull());
		verify(harness.billingService(), times(1)).deductForTokenUsage(
			anyInt(), anyInt(), anyInt(), eq("test-model"), eq("c1"));
		verify(harness.toolCallLogService(), times(1))
			.attachMessageId(eq("t1"), eq("assistant-message"));
	}

	/**
	 * 验证主回答与图表选择请求的 Token 合并后只保存和扣费一次。
	 */
	@Test
	public void shouldMergeSelectionTokensAndFinalizeOnce() {
		TestHarness harness = buildHarness();
		when(harness.chartSelectionService().ensureChart(
			anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
			.thenReturn(this.response("{\"type\":\"bar\"}", 4, 5, 9));
		when(harness.historyService().saveAssistantMessageAndUpdateStats(
			anyString(), anyString(), anyString(), anyString(),
			anyInt(), anyInt(), anyInt(), nullable(String.class), anyInt(),
			isNull(), anyInt(), any(), eq("success"), isNull()))
			.thenReturn("assistant-message");
		TenantContext.setEntCode("ENT001");
		TenantContext.setUserId("U001");

		harness.lifecycleService().finishNonStreaming(
			"测试问题", "c1", "data", "test-model-id", "test-model",
			this.response("<!--FINAL_ANSWER-->模型回答", 1, 2, 3), 20L, "t1");

		verify(harness.historyService(), times(1)).saveAssistantMessageAndUpdateStats(
			eq("c1"), eq("模型回答"), eq("data"), eq("test-model"),
			eq(5), eq(7), eq(12), nullable(String.class), anyInt(),
			isNull(), anyInt(), any(), eq("success"), isNull());
		verify(harness.billingService(), times(1)).deductForTokenUsage(
			eq(12), eq(5), eq(7), eq("test-model"), eq("c1"));
	}

	/**
	 * 构造非流式测试服务及其可观测依赖。
	 *
	 * @return 测试夹具
	 */
	private TestHarness buildHarness() {
		ChatHistoryService historyService = mock(ChatHistoryService.class);
		BillingService billingService = mock(BillingService.class);
		ToolCallLogService toolCallLogService = mock(ToolCallLogService.class);
		ChartSelectionService chartSelectionService = mock(ChartSelectionService.class);
		ToolResultRecorder recorder = new ToolResultRecorder();
		ChartSpecCodec codec = new ChartSpecCodec();
		AssistantLifecycleService lifecycleService = new AssistantLifecycleService(
			historyService, billingService, new ToolCallRecorder(),
			toolCallLogService, recorder, codec, new AssistantAnswerSanitizer(),
			chartSelectionService);
		return new TestHarness(
			lifecycleService, historyService, billingService, toolCallLogService,
			chartSelectionService, recorder);
	}

	/**
	 * 为固定 trace 放入可发布图表。
	 *
	 * @param recorder Tool 结果记录器
	 */
	private void prepareChart(ToolResultRecorder recorder) {
		recorder.capture("t1", "ENT001", "c1",
			"query_sales", "database", "[{\"category\":\"A\",\"value\":10}]");
		ChartVO.ChartSpec chart = new ChartVO.ChartSpec(
			ChartVO.SCHEMA_VERSION, "chart-1", ChartVO.ChartType.BAR, "销售额", null,
			new ChartVO.Dataset(
				List.of(
					new ChartVO.Dimension("category", "分类", "string", null),
					new ChartVO.Dimension("value", "数值", "number", "元")),
				List.of(Map.of("category", "A", "value", 10))),
			Map.of("category", List.of("category"), "value", List.of("value")),
			null, new ChartVO.ChartSource(List.of("query_sales")));
		assertThat(recorder.acceptChart("t1", "ENT001", "c1", chart)).isTrue();
	}

	/**
	 * 调用服务非流式收口方法。
	 *
	 * @param lifecycleService 问答生命周期服务
	 * @return 回答结果
	 */
	private ChatAnswerResult invokeRecordAndReturn(AssistantLifecycleService lifecycleService) {
		ChatResponse response = new ChatResponse(
			List.of(new Generation(new AssistantMessage(
				"让我查询数据并规划图表。<!--FINAL_ANSWER-->模型回答"))));
		return lifecycleService.finishNonStreaming(
			"测试问题", "c1", "data", "test-model-id", "test-model", response, 20L, "t1");
	}

	/**
	 * 非流式测试夹具。
	 *
	 * @param lifecycleService   问答生命周期服务
	 * @param historyService     历史服务
	 * @param billingService     计费服务
	 * @param toolCallLogService Tool 调用流水服务
	 * @param chartSelectionService 图表选择服务
	 * @param recorder           Tool 结果记录器
	 */
	private record TestHarness(AssistantLifecycleService lifecycleService,
			ChatHistoryService historyService,
			BillingService billingService, ToolCallLogService toolCallLogService,
			ChartSelectionService chartSelectionService,
			ToolResultRecorder recorder) {
	}

	/**
	 * 构造带 Token 用量的测试响应。
	 *
	 * @param text             回答文本
	 * @param promptTokens     输入 Token
	 * @param completionTokens 输出 Token
	 * @param totalTokens      总 Token
	 * @return 测试响应
	 */
	private ChatResponse response(String text, int promptTokens, int completionTokens, int totalTokens) {
		ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
		ChatResponseMetadata metadata = ChatResponseMetadata.builder()
			.usage(new DefaultUsage(promptTokens, completionTokens, totalTokens))
			.build();
		return ChatResponse.builder().from(response).metadata(metadata).build();
	}

}
