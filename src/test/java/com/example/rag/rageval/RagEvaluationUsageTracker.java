package com.example.rag.rageval;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import reactor.core.publisher.Flux;

/**
 * 记录 LLM Judge 模型调用 Token 的透明代理。
 */
final class RagEvaluationUsageTracker implements ChatModel {

	/** 实际 Judge 模型。 */
	private final ChatModel delegate;

	/** 累计 Judge 输入 Token。 */
	private final AtomicInteger promptTokens = new AtomicInteger();

	/** 累计 Judge 输出 Token。 */
	private final AtomicInteger completionTokens = new AtomicInteger();

	/**
	 * 创建 Judge 用量记录代理。
	 *
	 * @param delegate 实际模型
	 */
	public RagEvaluationUsageTracker(ChatModel delegate) {
		this.delegate = delegate;
	}

	/**
	 * 调用实际模型并记录本次返回的 Token。
	 *
	 * @param prompt 模型提示词
	 * @return 实际模型响应
	 */
	@Override
	public ChatResponse call(Prompt prompt) {
		ChatResponse response = this.delegate.call(prompt);
		recordUsage(response);
		return response;
	}

	/**
	 * 调用实际流式模型并记录可观测的 Token。
	 *
	 * @param prompt 模型提示词
	 * @return 实际模型响应流
	 */
	@Override
	public Flux<ChatResponse> stream(Prompt prompt) {
		return this.delegate.stream(prompt).doOnNext(this::recordUsage);
	}

	/**
	 * 返回实际模型的调用选项。
	 *
	 * @return 模型调用选项
	 */
	@Override
	public ChatOptions getOptions() {
		return this.delegate.getOptions();
	}

	/**
	 * 返回当前累计用量快照。
	 *
	 * @return 累计用量快照
	 */
	public UsageSnapshot snapshot() {
		return new UsageSnapshot(this.promptTokens.get(), this.completionTokens.get());
	}

	/**
	 * 记录单次响应中的非负 Token。
	 *
	 * @param response 模型响应
	 */
	private void recordUsage(ChatResponse response) {
		Usage usage = response == null || response.getMetadata() == null
			? null : response.getMetadata().getUsage();
		if (usage == null) {
			return;
		}
		this.promptTokens.addAndGet(nonNegative(usage.getPromptTokens()));
		this.completionTokens.addAndGet(nonNegative(usage.getCompletionTokens()));
	}

	/**
	 * 将可空 Token 规范为非负数。
	 *
	 * @param value 原始 Token
	 * @return 非负 Token
	 */
	private int nonNegative(Integer value) {
		return value == null ? 0 : Math.max(value, 0);
	}

	/**
	 * Judge 模型累计用量快照。
	 *
	 * @param promptTokens     输入 Token
	 * @param completionTokens 输出 Token
	 */
	public record UsageSnapshot(int promptTokens, int completionTokens) {

		/**
		 * 计算相对上一快照新增的 Token。
		 *
		 * @param previous 上一快照
		 * @return 新增用量
		 */
		public UsageSnapshot subtract(UsageSnapshot previous) {
			return new UsageSnapshot(
				Math.max(this.promptTokens - previous.promptTokens, 0),
				Math.max(this.completionTokens - previous.completionTokens, 0));
		}

	}

}
