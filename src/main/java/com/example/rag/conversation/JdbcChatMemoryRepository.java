package com.example.rag.conversation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 基于 a_chat_message 表的 {@link ChatMemoryRepository} 实现。
 * <p>
 * 复用已有的对话持久化数据，内存零占用，应用重启不丢失对话历史。
 * <p>
 * 设计要点：
 * <ul>
 *   <li><b>读操作（findByConversationId）</b>：从 DB 加载最近 N 条消息，并剔除末尾的 user
 *       消息——因为 {@code prepareConversation()} 在 Advisor 读取前已将当前提问写入 DB，
 *       而 Advisor 会将当前提问作为 prompt 指令单独发送，不剔除会导致重复</li>
 *   <li><b>写操作（saveAll）</b>：空操作，消息写入统一由 {@link ChatHistoryService} 负责</li>
 *   <li><b>删除操作（deleteByConversationId）</b>：空操作，归档由 {@link ChatHistoryService#archiveConversation} 负责</li>
 * </ul>
 */
@Component
public class JdbcChatMemoryRepository implements ChatMemoryRepository {

	private static final int MAX_HISTORY = 20;

	private final JdbcTemplate erp;

	public JdbcChatMemoryRepository(@Qualifier("erpJdbcTemplate") JdbcTemplate erpJdbcTemplate) {
		this.erp = erpJdbcTemplate;
	}

	@Override
	public List<Message> findByConversationId(String conversationId) {
		List<Message> messages = new ArrayList<>(erp.query(
			"SELECT role, content FROM (" +
				"SELECT role, content, created_at FROM a_chat_message " +
				"WHERE conversation_id = ? AND content IS NOT NULL AND status = 'success' " +
				"ORDER BY created_at DESC LIMIT ?" +
			") t ORDER BY created_at ASC",
			(rs, rowNum) -> {
				String role = rs.getString("role");
				String content = rs.getString("content");
				if ("user".equals(role)) {
					return (Message) new UserMessage(content);
				}
				return (Message) new AssistantMessage(content);
			},
			conversationId, MAX_HISTORY));

		// prepareConversation() 在 Advisor 之前已将当前 user 消息写入 DB，
		// Advisor 会将当前提问作为 prompt 指令单独发送，这里需要剔除以避免重复。
		if (!messages.isEmpty() && messages.get(messages.size() - 1) instanceof UserMessage) {
			messages.remove(messages.size() - 1);
		}

		return messages;
	}

	@Override
	public void saveAll(String conversationId, List<Message> messages) {
		// 空操作：消息持久化由 ChatHistoryService 统一负责
	}

	@Override
	public void deleteByConversationId(String conversationId) {
		// 空操作：会话归档由 ChatHistoryService.archiveConversation() 负责
	}

	@Override
	public List<String> findConversationIds() {
		return Collections.emptyList();
	}

}
