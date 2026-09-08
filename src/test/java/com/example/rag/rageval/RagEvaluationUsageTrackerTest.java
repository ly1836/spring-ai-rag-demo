package com.example.rag.rageval;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RAG Judge 模型用量记录测试。
 */
class RagEvaluationUsageTrackerTest {

	/**
	 * 验证代理保留模型响应并累计可观测 Token。
	 */
	@Test
	public void shouldTrackBlockingJudgeUsage() {
		ChatModel delegate = mock(ChatModel.class);
		ChatResponse response = mock(ChatResponse.class);
		ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
		Usage usage = mock(Usage.class);
		Prompt prompt = new Prompt("评测问题");
		when(delegate.call(prompt)).thenReturn(response);
		when(response.getMetadata()).thenReturn(metadata);
		when(metadata.getUsage()).thenReturn(usage);
		when(usage.getPromptTokens()).thenReturn(12);
		when(usage.getCompletionTokens()).thenReturn(3);
		RagEvaluationUsageTracker tracker = new RagEvaluationUsageTracker(delegate);

		ChatResponse actual = tracker.call(prompt);

		assertThat(actual).isSameAs(response);
		assertThat(tracker.snapshot())
			.isEqualTo(new RagEvaluationUsageTracker.UsageSnapshot(12, 3));
	}

}
