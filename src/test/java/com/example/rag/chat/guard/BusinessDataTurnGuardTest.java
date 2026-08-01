package com.example.rag.chat.guard;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.rag.chat.chart.capture.ToolResultRecorder;
import com.example.rag.tool.trace.ToolCallRecorder;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 当前轮业务数据守卫测试。
 */
class BusinessDataTurnGuardTest {

	/**
	 * 验证数据模式首次没有本轮业务结果时只重试一次，并采用重试回答。
	 */
	@Test
	public void shouldRetryOnceAndUseRetryResponseWhenCurrentTurnDataMissing() {
		ToolResultRecorder recorder = new ToolResultRecorder();
		BusinessDataTurnGuard guard = new BusinessDataTurnGuard(recorder, new ToolCallRecorder());
		AtomicInteger retryCount = new AtomicInteger();

		ChatResponse response = guard.ensureNonStreaming(
			this.response("历史回答", 1, 2, 3),
			() -> {
				retryCount.incrementAndGet();
				recorder.capture("t1", "ENT001", "c1", "query_orders", "database",
					"[{\"status\":\"已完成\",\"count\":3},{\"status\":\"待处理\",\"count\":1}]");
				return this.response("当前轮回答", 4, 5, 9);
			},
			"data", "近期售后工单处理情况怎么样？", "t1", "ENT001", "c1");

		assertThat(retryCount).hasValue(1);
		assertThat(response.getResult().getOutput().getText()).isEqualTo("当前轮回答");
		assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(5);
		assertThat(response.getMetadata().getUsage().getCompletionTokens()).isEqualTo(7);
		assertThat(response.getMetadata().getUsage().getTotalTokens()).isEqualTo(12);
	}

	/**
	 * 验证首次调用已经取得当前轮业务结果时不会重复查询。
	 */
	@Test
	public void shouldKeepInitialResponseWhenCurrentTurnDataExists() {
		ToolResultRecorder recorder = new ToolResultRecorder();
		BusinessDataTurnGuard guard = new BusinessDataTurnGuard(recorder, new ToolCallRecorder());
		AtomicInteger retryCount = new AtomicInteger();
		recorder.capture("t1", "ENT001", "c1", "query_orders", "database",
			"[{\"status\":\"已完成\",\"count\":3}]");

		ChatResponse response = guard.ensureNonStreaming(
			this.response("当前轮回答", 1, 2, 3),
			() -> {
				retryCount.incrementAndGet();
				return this.response("不应使用", 4, 5, 9);
			},
			"data", "近期售后工单处理情况怎么样？", "t1", "ENT001", "c1");

		assertThat(retryCount).hasValue(0);
		assertThat(response.getResult().getOutput().getText()).isEqualTo("当前轮回答");
	}

	/**
	 * 验证知识模式不进入业务查询重试。
	 */
	@Test
	public void shouldNotRetryKnowledgeMode() {
		BusinessDataTurnGuard guard = new BusinessDataTurnGuard(
			new ToolResultRecorder(), new ToolCallRecorder());
		AtomicInteger retryCount = new AtomicInteger();

		ChatResponse response = guard.ensureNonStreaming(
			this.response("产品说明", 1, 2, 3),
			() -> {
				retryCount.incrementAndGet();
				return this.response("不应使用", 4, 5, 9);
			},
			"knowledge", "这个产品如何安装？", "t1", "ENT001", "c1");

		assertThat(retryCount).hasValue(0);
		assertThat(response.getResult().getOutput().getText()).isEqualTo("产品说明");
	}

	/**
	 * 验证流式首次回答没有本轮数据时不会向下游泄漏，而是改用重试流。
	 */
	@Test
	public void shouldSuppressInitialStreamAndUseRetryStream() {
		ToolResultRecorder recorder = new ToolResultRecorder();
		BusinessDataTurnGuard guard = new BusinessDataTurnGuard(recorder, new ToolCallRecorder());
		AtomicInteger retryCount = new AtomicInteger();

		List<ChatResponse> responses = guard.ensureStreaming(
			Flux.just(this.response("历史", 1, 2, 3), this.response("表格", 0, 0, 0)),
			() -> Flux.defer(() -> {
				retryCount.incrementAndGet();
				recorder.capture("t1", "ENT001", "c1", "query_orders", "database",
					"[{\"status\":\"已完成\",\"count\":3},{\"status\":\"待处理\",\"count\":1}]");
				return Flux.just(this.response("当前轮", 4, 5, 9));
			}),
			"auto", "近期售后工单处理情况怎么样？", "t1", "ENT001", "c1")
			.collectList()
			.block();

		assertThat(retryCount).hasValue(1);
		assertThat(responses).hasSize(1);
		assertThat(responses.get(0).getResult().getOutput().getText()).isEqualTo("当前轮");
		assertThat(responses.get(0).getMetadata().getUsage().getTotalTokens()).isEqualTo(12);
	}

	/**
	 * 验证首次流在结束前捕获业务结果后立即释放响应，不等待完整上游结束。
	 */
	@Test
	public void shouldReleaseInitialStreamBeforeSourceCompletesWhenBusinessResultArrives() {
		ToolResultRecorder recorder = new ToolResultRecorder();
		BusinessDataTurnGuard guard = new BusinessDataTurnGuard(recorder, new ToolCallRecorder());
		AtomicInteger retryCount = new AtomicInteger();
		Flux<ChatResponse> initialFlux = Flux.concat(
			Flux.just(this.response("内部调用", 0, 0, 0)),
			Flux.defer(() -> {
				recorder.capture("t1", "ENT001", "c1", "query_orders", "database",
					"[{\"status\":\"已完成\",\"count\":3}]");
				return Flux.just(this.response("第一段", 1, 2, 3));
			}),
			// 永不结束的尾流用于证明守卫没有等待 collectList 完成。
			Flux.<ChatResponse>never());

		ChatResponse response = guard.ensureStreaming(
			initialFlux,
			() -> {
				retryCount.incrementAndGet();
				return Flux.just(this.response("不应使用", 4, 5, 9));
			},
			"data", "近期售后工单处理情况怎么样？", "t1", "ENT001", "c1")
			.next()
			.block(Duration.ofSeconds(2));

		assertThat(retryCount).hasValue(0);
		assertThat(response.getResult().getOutput().getText()).isEqualTo("第一段");
	}

	/**
	 * 验证有界重试确认业务结果后也逐分片发送，不等待重试流结束。
	 */
	@Test
	public void shouldReleaseRetryStreamBeforeSourceCompletesWhenBusinessResultArrives() {
		ToolResultRecorder recorder = new ToolResultRecorder();
		BusinessDataTurnGuard guard = new BusinessDataTurnGuard(recorder, new ToolCallRecorder());
		AtomicInteger retryCount = new AtomicInteger();

		ChatResponse response = guard.ensureStreaming(
			Flux.just(this.response("历史回答", 1, 2, 3)),
			() -> Flux.concat(
				Flux.defer(() -> {
					retryCount.incrementAndGet();
					recorder.capture("t1", "ENT001", "c1", "query_orders", "database",
						"[{\"status\":\"已完成\",\"count\":3}]");
					return Flux.just(this.response("当前轮第一段", 4, 5, 9));
				}),
				// 重试响应发出后保持连接，用于验证 Token 合并不会阻塞正文分片。
				Flux.<ChatResponse>never()),
			"data", "近期售后工单处理情况怎么样？", "t1", "ENT001", "c1")
			.next()
			.block(Duration.ofSeconds(2));

		assertThat(retryCount).hasValue(1);
		assertThat(response.getResult().getOutput().getText()).isEqualTo("当前轮第一段");
		assertThat(response.getMetadata().getUsage().getTotalTokens()).isEqualTo(12);
	}

	/**
	 * 构造带 Token 用量的测试模型响应。
	 *
	 * @param text             回答文本
	 * @param promptTokens     输入 Token
	 * @param completionTokens 输出 Token
	 * @param totalTokens      总 Token
	 * @return 测试模型响应
	 */
	private ChatResponse response(String text, int promptTokens, int completionTokens, int totalTokens) {
		ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
		ChatResponseMetadata metadata = ChatResponseMetadata.builder()
			.usage(new DefaultUsage(promptTokens, completionTokens, totalTokens))
			.build();
		return ChatResponse.builder().from(response).metadata(metadata).build();
	}

	/**
	 * 验证自动模式可识别英文及中英混合的 ERP 数据查询，同时不误判英文产品安装问题。
	 */
	@Test
	public void shouldRecognizeEnglishBusinessDataQuestionsInAutoMode() {
		BusinessDataTurnGuard guard = new BusinessDataTurnGuard(
			new ToolResultRecorder(), new ToolCallRecorder());

		assertThat(guard.requiresCurrentBusinessData("auto", "What are the latest sales orders?"))
			.isTrue();
		assertThat(guard.requiresCurrentBusinessData("auto", "What are the sales orders?"))
			.isTrue();
		assertThat(guard.requiresCurrentBusinessData("auto", "Give me sales orders"))
			.isTrue();
		assertThat(guard.requiresCurrentBusinessData("auto", "Show me 最近的 sales orders"))
			.isTrue();
		assertThat(guard.requiresCurrentBusinessData("auto", "How do I install this product?"))
			.isFalse();
		assertThat(guard.requiresCurrentBusinessData("auto", "What are the product specifications?"))
			.isFalse();
	}
}
