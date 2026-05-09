package com.example.rag.conversation;

import java.util.List;

import com.example.rag.config.TenantContext;
import com.example.rag.dao.entity.ChatConversationEntity;
import com.example.rag.dao.entity.ChatMessageEntity;
import com.example.rag.dao.mapper.ChatConversationMapper;
import com.example.rag.dao.mapper.ChatMessageMapper;
import com.example.rag.vo.ConversationVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对话历史服务测试。
 */
class ChatHistoryServiceTest {

	private ChatConversationMapper conversationMapper;
	private ChatMessageMapper messageMapper;
	private ChatHistoryService service;

	@BeforeEach
	void setUp() {
		conversationMapper = mock(ChatConversationMapper.class);
		messageMapper = mock(ChatMessageMapper.class);
		service = new ChatHistoryService(conversationMapper, messageMapper);
		TenantContext.setEntCode("ENT001");
		TenantContext.setUserId("U002");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
	}

	/**
	 * 验证新会话会幂等创建并保存用户消息。
	 */
	@Test
	void shouldInitConversationAndSaveUserMessage() {
		String messageId = service.initConversationAndSaveUserMessage("c1", "测试问题", "auto");

		assertThat(messageId).isNotBlank();
		verify(conversationMapper).insertIgnore("c1", "ENT001", "U002", "测试问题", "auto");
		verify(messageMapper).insert(any(ChatMessageEntity.class));
	}

	/**
	 * 验证会话列表查询保留当前用户维度。
	 */
	@Test
	void shouldListCurrentUserConversations() {
		when(conversationMapper.selectConversationItems("U002", 20, 0)).thenReturn(List.of(
			new ConversationVO.ConversationItemResponse("c1", "标题", "auto", 1, 10, "active",
				"2026-05-08 10:00:00", "2026-05-08 10:00:00")));

		List<ConversationVO.ConversationItemResponse> result = service.getConversations(0, 20);

		assertThat(result).hasSize(1);
		verify(conversationMapper).selectConversationItems("U002", 20, 0);
	}

	/**
	 * 验证删除会话仍使用软删除。
	 */
	@Test
	void shouldArchiveConversationBySoftDelete() {
		service.archiveConversation("c1");

		verify(conversationMapper).update(any(ChatConversationEntity.class), any());
	}

	/**
	 * 验证已删除会话不可继续。
	 */
	@Test
	void shouldRejectDeletedConversation() {
		when(conversationMapper.selectStatus("c1")).thenReturn(List.of("deleted"));

		assertThatThrownBy(() -> service.requireConversationActive("c1", true))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("会话已删除，不可继续");
	}

	/**
	 * 验证续聊时目标会话不存在会被拒绝。
	 */
	@Test
	void shouldRejectMissingConversationWhenRequired() {
		when(conversationMapper.selectStatus("missing")).thenReturn(List.of());

		assertThatThrownBy(() -> service.requireConversationActive("missing", true))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("会话不存在，不可继续");
	}
}
