package com.example.rag.dao.mapper;

import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.rag.dao.entity.ChatMessageEntity;
import com.example.rag.vo.ConversationVO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 对话消息。Mapper。
 */
public interface ChatMessageMapper extends BaseMapper<ChatMessageEntity> {

	/**
	 * 查询指定会话消息详情。
	 *
	 * @param conversationId 会话 ID
	 * @return 消息详情列表
	 */
	@Select("""
		SELECT message_id AS messageId, role, content, mode, model,
		prompt_tokens AS promptTokens, completion_tokens AS completionTokens, total_tokens AS totalTokens,
		tool_calls AS toolCalls, tool_calls_count AS toolCallsCount, chart_spec AS chartSpec,
		rag_doc_count AS ragDocCount,
		duration_ms AS durationMs, status, error_message AS errorMessage,
		DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS createdAt
		FROM a_chat_message
		WHERE conversation_id = #{conversationId}
		ORDER BY created_at ASC
		""")
	List<ConversationVO.ChatMessageRecord> selectMessageItems(@Param("conversationId") String conversationId);

	/**
	 * 查询最近成功消息，。Spring AI ChatMemory 使用。
	 *
	 * @param conversationId 会话 ID
	 * @param limit          最大消息数
	 * @return role/content 列表
	 */
	@Select("""
		SELECT role, content FROM (
			SELECT role, content, created_at FROM a_chat_message
			WHERE conversation_id = #{conversationId} AND content IS NOT NULL AND status = 'success'
			ORDER BY created_at DESC LIMIT #{limit}
		) t ORDER BY created_at ASC
		""")
	List<Map<String, Object>> selectRecentMessages(
			@Param("conversationId") String conversationId, @Param("limit") int limit);
}
