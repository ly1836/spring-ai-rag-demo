package com.example.rag.conversation;

import java.util.List;
import java.util.Map;

import com.example.rag.chat.chart.protocol.ChartSpecCodec;
import com.example.rag.chat.output.AssistantAnswerSanitizer;
import com.example.rag.config.TenantContext;
import com.example.rag.dao.entity.ChatConversationEntity;
import com.example.rag.dao.entity.ChatMessageEntity;
import com.example.rag.dao.mapper.ChatConversationMapper;
import com.example.rag.dao.mapper.ChatMessageMapper;
import com.example.rag.vo.ConversationVO;
import com.example.rag.vo.ChartVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.ArgumentCaptor;

/**
 * 对话历史服务测试。
 */
class ChatHistoryServiceTest {

	private ChatConversationMapper conversationMapper;
	private ChatMessageMapper messageMapper;
	private ChartSpecCodec chartSpecCodec;
	private ChatHistoryService service;

	@BeforeEach
	void setUp() {
		conversationMapper = mock(ChatConversationMapper.class);
		messageMapper = mock(ChatMessageMapper.class);
		chartSpecCodec = new ChartSpecCodec();
		service = new ChatHistoryService(
			conversationMapper, messageMapper, chartSpecCodec, new AssistantAnswerSanitizer());
		TenantContext.setEntCode("ENT001");
		TenantContext.setUserId("U002");
		// 默认测试会话属于当前用户，需要异常状态的用例再单独覆盖。
		when(conversationMapper.selectStatus("c1", "U002")).thenReturn(List.of("active"));
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
		when(conversationMapper.selectStatus("c1", "U002")).thenReturn(List.of("deleted"));

		assertThatThrownBy(() -> service.requireConversationActive("c1", true))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("会话已删除，不可继续");
	}

	/**
	 * 验证续聊时目标会话不存在会被拒绝。
	 */
	@Test
	void shouldRejectMissingConversationWhenRequired() {
		when(conversationMapper.selectStatus("missing", "U002")).thenReturn(List.of());

		assertThatThrownBy(() -> service.requireConversationActive("missing", true))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("会话不存在，不可继续");
	}

	/**
	 * 验证历史消息会恢复已持久化的图表协议。
	 */
	@Test
	public void shouldRestorePersistedChartFromHistory() {
		ChartVO.ChartSpec chart = new ChartVO.ChartSpec(
			ChartVO.SCHEMA_VERSION, "chart-1", ChartVO.ChartType.BAR, "销售额", null,
			new ChartVO.Dataset(
				List.of(
					new ChartVO.Dimension("category", "名称", "string", null),
					new ChartVO.Dimension("value", "销售额", "number", "元")),
				List.of(Map.of("category", "产品A", "value", 100))),
			Map.of("category", List.of("category"), "value", List.of("value")), null,
			new ChartVO.ChartSource(List.of("query_sales")));
		when(messageMapper.selectMessageItems("c1")).thenReturn(List.of(
			new ConversationVO.ChatMessageRecord("m1", "assistant", "回答", "data", "model",
				0, 0, 0, null, 0, chartSpecCodec.encode(chart), 0,
				10, "success", null, "2026-07-31 10:00:00")));

		List<ConversationVO.ChatMessageItemResponse> messages = service.getMessages("c1");

		assertThat(messages).singleElement().extracting(ConversationVO.ChatMessageItemResponse::chart)
			.isEqualTo(chart);
	}

	/**
	 * 验证旧消息的空图表字段保持为空。
	 */
	@Test
	public void shouldKeepLegacyMessageChartNull() {
		when(messageMapper.selectMessageItems("c1")).thenReturn(List.of(
			new ConversationVO.ChatMessageRecord("m1", "assistant", "回答", "data", "model",
				0, 0, 0, null, 0, null, 0,
				10, "success", null, "2026-07-31 10:00:00")));

		List<ConversationVO.ChatMessageItemResponse> messages = service.getMessages("c1");

		assertThat(messages).singleElement().extracting(ConversationVO.ChatMessageItemResponse::chart)
			.isNull();
	}

	/**
	 * 验证损坏的历史图表 JSON 会安全降级为空。
	 */
	@Test
	public void shouldIgnoreInvalidPersistedChart() {
		when(messageMapper.selectMessageItems("c1")).thenReturn(List.of(
			new ConversationVO.ChatMessageRecord("m1", "assistant", "回答", "data", "model",
				0, 0, 0, null, 0, "{invalid", 0,
				10, "success", null, "2026-07-31 10:00:00")));

		List<ConversationVO.ChatMessageItemResponse> messages = service.getMessages("c1");

		assertThat(messages).singleElement().extracting(ConversationVO.ChatMessageItemResponse::chart)
			.isNull();
	}

	/**
	 * 验证助手图表 JSON 与文本在同一消息实体中写入。
	 */
	@Test
	public void shouldPersistChartJsonOnAssistantMessage() {
		service.saveAssistantMessageAndUpdateStats(
			"c1", "回答", "data", "model", 1, 2, 3,
			"[]", 0, "{\"schemaVersion\":\"1.0\"}", 0, 10);
		ArgumentCaptor<ChatMessageEntity> entityCaptor = ArgumentCaptor.forClass(ChatMessageEntity.class);

		verify(messageMapper).insert(entityCaptor.capture());

		assertThat(entityCaptor.getValue().getChartSpec()).isEqualTo("{\"schemaVersion\":\"1.0\"}");
	}

	/**
	 * 验证租户过滤后不可见的会话会在读取消息前被续聊校验拒绝。
	 */
	@Test
	public void shouldRejectCrossTenantConversationBeforeReadingMessages() {
		when(conversationMapper.selectStatus("other-tenant-conversation", "U002")).thenReturn(List.of());

		assertThatThrownBy(() -> service.getMessages("other-tenant-conversation"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("会话不存在，不可继续");
		verify(messageMapper, never()).selectMessageItems("other-tenant-conversation");
	}

	/**
	 * 验证取消和错误消息的历史图表保持为空。
	 */
	@Test
	public void shouldKeepCancelledAndErrorMessageChartsNull() {
		when(messageMapper.selectMessageItems("c1")).thenReturn(List.of(
			new ConversationVO.ChatMessageRecord("m1", "assistant", "部分回答", "data", "model",
				0, 0, 0, null, 0, null, 0,
				10, "cancelled", null, "2026-07-31 10:00:00"),
			new ConversationVO.ChatMessageRecord("m2", "assistant", null, "data", "model",
				0, 0, 0, null, 0, null, 0,
				10, "error", "模型异常", "2026-07-31 10:01:00")));

		List<ConversationVO.ChatMessageItemResponse> messages = service.getMessages("c1");

		assertThat(messages).extracting(ConversationVO.ChatMessageItemResponse::chart)
			.containsOnlyNulls();
	}

	/**
	 * 验证当前用户不能归档同租户其他用户的会话。
	 */
	@Test
	public void shouldRejectCrossUserConversationBeforeArchive() {
		when(conversationMapper.selectStatus("other-user-conversation", "U002")).thenReturn(List.of());

		assertThatThrownBy(() -> service.archiveConversation("other-user-conversation"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("会话不存在，不可继续");
		verify(conversationMapper, never()).update(any(ChatConversationEntity.class), any());
	}

	/**
	 * 验证历史助手消息不会返回最终答案之前的内部执行旁白。
	 */
	@Test
	public void shouldSanitizeAssistantNarrationWhenReadingHistory() {
		when(messageMapper.selectMessageItems("c1")).thenReturn(List.of(
			new ConversationVO.ChatMessageRecord(
				"m1", "assistant", "让我规划图表。\n\n---\n\n最终业务回答",
				"data", "model", 0, 0, 0, null, 0, null, 0,
				10, "success", null, "2026-07-31 10:00:00"),
			new ConversationVO.ChatMessageRecord(
				"m2", "user", "让我查询订单",
				"data", null, 0, 0, 0, null, 0, null, 0,
				null, "success", null, "2026-07-31 10:01:00")));

		List<ConversationVO.ChatMessageItemResponse> messages = service.getMessages("c1");

		assertThat(messages).extracting(ConversationVO.ChatMessageItemResponse::content)
			.containsExactly("最终业务回答", "让我查询订单");
	}
}
