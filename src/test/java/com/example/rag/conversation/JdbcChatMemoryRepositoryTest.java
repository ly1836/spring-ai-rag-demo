package com.example.rag.conversation;

import java.util.List;
import java.util.Map;

import com.example.rag.dao.mapper.ChatMessageMapper;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ChatMemory 数据库仓库测试。
 */
class JdbcChatMemoryRepositoryTest {

	/**
	 * 验证加载历史时会剔除末尾用户消息，避免当前问题重复进入 Prompt。
	 */
	@Test
	void shouldRemoveTrailingUserMessage() {
		ChatMessageMapper mapper = mock(ChatMessageMapper.class);
		when(mapper.selectRecentMessages("c1", 20)).thenReturn(List.of(
			Map.of("role", "user", "content", "上一问"),
			Map.of("role", "assistant", "content", "上一答"),
			Map.of("role", "user", "content", "当前问题")));

		JdbcChatMemoryRepository repository = new JdbcChatMemoryRepository(mapper);

		List<Message> messages = repository.findByConversationId("c1");

		assertThat(messages).hasSize(2);
		assertThat(messages.get(0)).isInstanceOf(UserMessage.class);
		assertThat(messages.get(1)).isInstanceOf(AssistantMessage.class);
	}
}
