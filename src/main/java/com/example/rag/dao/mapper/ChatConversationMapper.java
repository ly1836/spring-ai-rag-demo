package com.example.rag.dao.mapper;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.rag.dao.entity.ChatConversationEntity;
import com.example.rag.vo.ConversationVO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 对话会话。Mapper。
 */
public interface ChatConversationMapper extends BaseMapper<ChatConversationEntity> {

	/**
	 * 幂等创建会话。
	 *
	 * @param conversationId 会话 ID
	 * @param entCode        租户编码
	 * @param userId         用户 ID
	 * @param title          会话标题
	 * @param mode           问答模式
	 * @return 影响行数
	 */
	@Insert("""
		INSERT IGNORE INTO a_chat_conversation
		(conversation_id, ent_code, user_id, title, mode)
		VALUES (#{conversationId}, #{entCode}, #{userId}, #{title}, #{mode})
		""")
	int insertIgnore(@Param("conversationId") String conversationId, @Param("entCode") String entCode,
			@Param("userId") String userId, @Param("title") String title, @Param("mode") String mode);

	/**
	 * 更新会话消息数和 token 统计。
	 *
	 * @param conversationId 会话 ID
	 * @return 影响行数
	 */
	@Update("""
		UPDATE a_chat_conversation SET
		message_count = (SELECT COUNT(*) FROM a_chat_message WHERE conversation_id = #{conversationId}),
		total_tokens = (SELECT COALESCE(SUM(total_tokens), 0) FROM a_chat_message WHERE conversation_id = #{conversationId}),
		updated_at = NOW()
		WHERE conversation_id = #{conversationId}
		""")
	int updateStats(@Param("conversationId") String conversationId);

	/**
	 * 查询当前用户会话列表。
	 *
	 * @param userId 用户 ID
	 * @param size   每页数量
	 * @param offset 偏移。
	 * @return 会话摘要列表
	 */
	@Select("""
		SELECT conversation_id AS conversationId, title, mode,
		message_count AS messageCount, total_tokens AS totalTokens, status,
		DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS createdAt,
		DATE_FORMAT(updated_at, '%Y-%m-%d %H:%i:%s') AS updatedAt
		FROM a_chat_conversation
		WHERE user_id = #{userId} AND status != 'deleted'
		ORDER BY updated_at DESC LIMIT #{size} OFFSET #{offset}
		""")
	List<ConversationVO.ConversationItemResponse> selectConversationItems(
			@Param("userId") String userId, @Param("size") int size, @Param("offset") int offset);

	/**
	 * 查询会话状态。
	 *
	 * @param conversationId 会话 ID
	 * @param userId         用户 ID
	 * @return 状态列。
	 */
	@Select("""
		SELECT status FROM a_chat_conversation
		WHERE conversation_id = #{conversationId} AND user_id = #{userId}
		""")
	List<String> selectStatus(@Param("conversationId") String conversationId,
			@Param("userId") String userId);
}
