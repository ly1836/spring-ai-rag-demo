package com.example.rag.chat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.example.rag.billing.BillingService;
import com.example.rag.chat.chart.capture.ToolResultRecorder;
import com.example.rag.chat.chart.protocol.ChartSpecCodec;
import com.example.rag.chat.chart.selection.ChartSelectionService;
import com.example.rag.chat.dto.ChatStreamFrame;
import com.example.rag.chat.lifecycle.AssistantLifecycleService;
import com.example.rag.chat.output.AssistantAnswerSanitizer;
import com.example.rag.config.TenantContext;
import com.example.rag.conversation.ChatHistoryService;
import com.example.rag.tool.trace.ToolCallLogService;
import com.example.rag.tool.trace.ToolCallRecorder;
import com.example.rag.vo.ChartVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 智能助手统一流式收口测试。
 */
class ErpAssistantStreamTest {

	/**
	 * 清理流式测试的租户上下文。
	 */
	@AfterEach
	public void tearDown() {
		TenantContext.clear();
	}

	/**
	 * 验证成功流按 delta、chart、done 顺序输出并只收口一次。
	 */
	@Test
	public void shouldEmitDeltaChartDoneAndFinalizeOnce() {
		TestHarness harness = buildHarness();
		prepareChart(harness.recorder(), "t1", "c1");
		TenantContext.setEntCode("ENT001");
		TenantContext.setUserId("U001");

		List<ChatStreamFrame> frames = invokeStream(harness.lifecycleService(),
			Flux.just(
				response("让我查询数据并规划图表。\n\n<!--FINAL_ANS"),
				response("WER-->第一行\n"), response("第二行")), "t1").collectList().block();

		assertThat(frames).extracting(ChatStreamFrame::event)
			.containsExactly("delta", "delta", "chart", "done");
		assertThat(((com.example.rag.vo.ChatVO.StreamDelta) frames.get(0).data()).text())
			.isEqualTo("第一行\n");
		verify(harness.historyService()).saveAssistantMessageAndUpdateStats(
			eq("c1"), eq("第一行\n第二行"), eq("data"), eq("test-model"),
			anyInt(), anyInt(), anyInt(), nullable(String.class), anyInt(),
			anyString(), anyInt(), any(), eq("success"), isNull());
		verify(harness.billingService()).deductForTokenUsage(
			anyInt(), anyInt(), anyInt(), eq("test-model"), eq("c1"));
		assertThat(harness.recorder().contextCount()).isZero();
	}

	/**
	 * 验证流内异常只输出安全 error，不发布图表或 done，也不扣费。
	 */
	@Test
	public void shouldEmitSafeErrorWithoutChartOrBilling() {
		TestHarness harness = buildHarness();
		prepareChart(harness.recorder(), "t1", "c1");
		TenantContext.setEntCode("ENT001");
		TenantContext.setUserId("U001");
		Flux<ChatResponse> upstream = Flux.concat(
			Flux.just(response("<!--FINAL_ANSWER-->部分文本")),
			Flux.error(new IllegalStateException("上游敏感异常")));

		List<ChatStreamFrame> frames = invokeStream(harness.lifecycleService(), upstream, "t1")
			.collectList().block();

		assertThat(frames).extracting(ChatStreamFrame::event)
			.containsExactly("delta", "error");
		assertThat(frames).noneMatch(frame -> "chart".equals(frame.event()) || "done".equals(frame.event()));
		verify(harness.historyService()).saveAssistantMessageAndUpdateStats(
			eq("c1"), eq("部分文本"), eq("data"), eq("test-model"),
			anyInt(), anyInt(), anyInt(), nullable(String.class), anyInt(),
			isNull(), anyInt(), any(), eq("error"), eq("上游敏感异常"));
		verify(harness.billingService(), never()).deductForTokenUsage(
			anyInt(), anyInt(), anyInt(), anyString(), anyString());
	}

	/**
	 * 验证用户取消会保存部分文本和 cancelled 状态，不保存图表且只结算一次。
	 */
	@Test
	public void shouldPersistPartialTextWithoutChartWhenCancelled() {
		TestHarness harness = buildHarness();
		prepareChart(harness.recorder(), "t1", "c1");
		TenantContext.setEntCode("ENT001");
		TenantContext.setUserId("U001");
		Flux<ChatResponse> upstream = Flux.concat(
			Flux.just(response("<!--FINAL_ANSWER-->已生成部分")),
			Flux.never());

		invokeStream(harness.lifecycleService(), upstream, "t1").take(1).blockLast();

		verify(harness.historyService(), timeout(2000).times(1)).saveAssistantMessageAndUpdateStats(
			eq("c1"), eq("已生成部分"), eq("data"), eq("test-model"),
			anyInt(), anyInt(), anyInt(), nullable(String.class), anyInt(),
			isNull(), anyInt(), any(), eq("cancelled"), isNull());
		verify(harness.billingService(), timeout(2000).times(1)).deductForTokenUsage(
			anyInt(), anyInt(), anyInt(), eq("test-model"), eq("c1"));
	}

	/**
	 * 构造流式测试服务及其可观测依赖。
	 *
	 * @return 测试夹具
	 */
	private TestHarness buildHarness() {
		ChatHistoryService historyService = mock(ChatHistoryService.class);
		BillingService billingService = mock(BillingService.class);
		ToolResultRecorder recorder = new ToolResultRecorder();
		ChartSpecCodec codec = new ChartSpecCodec();
		when(historyService.saveAssistantMessageAndUpdateStats(
			anyString(), anyString(), anyString(), anyString(),
			anyInt(), anyInt(), anyInt(), nullable(String.class), anyInt(),
			nullable(String.class), anyInt(), any(), anyString(), nullable(String.class)))
			.thenReturn("assistant-message");
		AssistantLifecycleService lifecycleService = new AssistantLifecycleService(
			historyService, billingService, new ToolCallRecorder(),
			mock(ToolCallLogService.class), recorder, codec, new AssistantAnswerSanitizer(),
			mock(ChartSelectionService.class));
		return new TestHarness(lifecycleService, historyService, billingService, recorder);
	}

	/**
	 * 为指定 trace 放入可发布的测试图表。
	 *
	 * @param recorder       Tool 结果记录器
	 * @param traceId        链路 ID
	 * @param conversationId 会话 ID
	 */
	private void prepareChart(ToolResultRecorder recorder, String traceId, String conversationId) {
		recorder.capture(traceId, "ENT001", conversationId,
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
		assertThat(recorder.acceptChart(traceId, "ENT001", conversationId, chart)).isTrue();
	}

	/**
	 * 构造流式模型响应。
	 *
	 * @param text 文本增量
	 * @return 模型响应
	 */
	private ChatResponse response(String text) {
		return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
	}

	/**
	 * 调用服务内部统一流式收口管道。
	 *
	 * @param lifecycleService 问答生命周期服务
	 * @param upstream 模型响应流
	 * @param traceId  链路 ID
	 * @return 类型化事件流
	 */
	private Flux<ChatStreamFrame> invokeStream(
			AssistantLifecycleService lifecycleService, Flux<ChatResponse> upstream, String traceId) {
		return lifecycleService.recordStream(
			upstream, "测试问题", "c1", "data", "test-model-id", "test-model", traceId);
	}

	/**
	 * 流式测试夹具。
	 *
	 * @param lifecycleService 问答生命周期服务
	 * @param historyService 历史服务
	 * @param billingService 计费服务
	 * @param recorder       Tool 结果记录器
	 */
	private record TestHarness(AssistantLifecycleService lifecycleService,
			ChatHistoryService historyService,
			BillingService billingService, ToolResultRecorder recorder) {
	}

	/**
	 * 验证 Provider 未输出最终答案边界时，净化文本仍先于图表和完成事件发送。
	 */
	@Test
	public void shouldEmitSanitizedFallbackBeforeChartAndDoneWithoutMarker() {
		TestHarness harness = buildHarness();
		prepareChart(harness.recorder(), "t1", "c1");
		TenantContext.setEntCode("ENT001");
		TenantContext.setUserId("U001");

		List<ChatStreamFrame> frames = invokeStream(harness.lifecycleService(),
			Flux.just(response("让我规划图表。\n\n---\n\n最终业务回答")), "t1")
			.collectList().block();

		assertThat(frames).extracting(ChatStreamFrame::event)
			.containsExactly("delta", "chart", "done");
		assertThat(((com.example.rag.vo.ChatVO.StreamDelta) frames.get(0).data()).text())
			.isEqualTo("最终业务回答");
		verify(harness.historyService()).saveAssistantMessageAndUpdateStats(
			eq("c1"), eq("最终业务回答"), eq("data"), eq("test-model"),
			anyInt(), anyInt(), anyInt(), nullable(String.class), anyInt(),
			anyString(), anyInt(), any(), eq("success"), isNull());
	}

	/**
	 * 验证无最终答案边界的安全正文在上游未完成时也能连续发送多个 delta。
	 */
	@Test
	public void shouldEmitMultipleDeltasBeforeSourceCompletesWithoutMarker() {
		TestHarness harness = buildHarness();
		TenantContext.setEntCode("ENT001");
		TenantContext.setUserId("U001");
		Flux<ChatResponse> upstream = Flux.concat(
			Flux.just(response("2026年3月"), response("共产生5笔售后工单")),
			// 永不结束的尾流用于证明正文没有等待 finish 阶段才一次性发送。
			Flux.<ChatResponse>never());

		List<ChatStreamFrame> frames = invokeStream(harness.lifecycleService(), upstream, "t1")
			.take(2)
			.collectList()
			.block(Duration.ofSeconds(2));

		assertThat(frames).extracting(ChatStreamFrame::event)
			.containsExactly("delta", "delta");
		assertThat(((com.example.rag.vo.ChatVO.StreamDelta) frames.get(0).data()).text())
			.isEqualTo("2026年3月");
		assertThat(((com.example.rag.vo.ChatVO.StreamDelta) frames.get(1).data()).text())
			.isEqualTo("共产生5笔售后工单");
	}

	/**
	 * 验证流内异常发生在最终答案标记中间时不会发送或保存协议片段。
	 */
	@Test
	public void shouldDiscardIncompleteFinalAnswerMarkerWhenStreamErrors() {
		TestHarness harness = buildHarness();
		TenantContext.setEntCode("ENT001");
		TenantContext.setUserId("U001");
		Flux<ChatResponse> upstream = Flux.concat(
			Flux.just(response("让我查询业务数据。\n\n<!--FINAL_ANS")),
			Flux.error(new IllegalStateException("上游异常")));

		List<ChatStreamFrame> frames = invokeStream(harness.lifecycleService(), upstream, "t1")
			.collectList().block();

		assertThat(frames).extracting(ChatStreamFrame::event).containsExactly("error");
		verify(harness.historyService()).saveAssistantMessageAndUpdateStats(
			eq("c1"), eq(""), eq("data"), eq("test-model"),
			anyInt(), anyInt(), anyInt(), nullable(String.class), anyInt(),
			isNull(), anyInt(), any(), eq("error"), eq("上游异常"));
	}

	/**
	 * 验证用户在最终答案标记中间取消时不会保存协议片段。
	 */
	@Test
	public void shouldDiscardIncompleteFinalAnswerMarkerWhenStreamIsCancelled() {
		TestHarness harness = buildHarness();
		TenantContext.setEntCode("ENT001");
		TenantContext.setUserId("U001");
		Flux<ChatResponse> upstream = Flux.concat(
			Flux.just(response("让我查询业务数据。\n\n<!--FINAL_ANS")),
			Flux.never());

		// 订阅后主动取消，触发生命周期服务的 CANCEL 收口。
		Disposable subscription = invokeStream(harness.lifecycleService(), upstream, "t1").subscribe();
		subscription.dispose();

		verify(harness.historyService(), timeout(2000).times(1)).saveAssistantMessageAndUpdateStats(
			eq("c1"), eq(""), eq("data"), eq("test-model"),
			anyInt(), anyInt(), anyInt(), nullable(String.class), anyInt(),
			isNull(), anyInt(), any(), eq("cancelled"), isNull());
	}

}
