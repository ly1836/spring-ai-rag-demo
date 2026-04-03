package com.example.rag.conversation;

import java.util.List;
import java.util.UUID;

import com.example.rag.config.TenantContext;
import com.example.rag.vo.ConversationVO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 对话记录服务 —— 管理 AI 问答的会话和消息持久化。
 * <p>
 * 所有写操作自动关联当前请求的 ent_code（租户）和 user_id（用户），
 * 读操作自动按租户隔离，确保多租户数据安全。
 * <p>
 * 事务策略：
 * <ul>
 *   <li>{@link #initConversationAndSaveUserMessage} — 会话创建 + 用户消息保存在同一事务</li>
 *   <li>{@link #saveAssistantMessageAndUpdateStats} — 助手消息保存 + 统计更新在同一事务</li>
 *   <li>计费扣除由 {@link com.example.rag.billing.BillingService} 在独立事务中处理，
 *       扣费失败不回滚已保存的消息</li>
 * </ul>
 * <p>
 * 数据表：a_chat_conversation（会话）、a_chat_message（消息）
 */
@Service
public class ChatHistoryService {

	private static final Logger log = LoggerFactory.getLogger(ChatHistoryService.class);

	private final JdbcTemplate erp;

	public ChatHistoryService(@Qualifier("erpJdbcTemplate") JdbcTemplate erpJdbcTemplate) {
		this.erp = erpJdbcTemplate;
	}

	// ==================== 事务化组合方法 ====================

	/**
	 * 【事务】初始化会话并保存用户消息，确保两个操作原子提交。
	 * <p>
	 * 在同一事务中执行：
	 * 1. INSERT IGNORE 会话记录（幂等，已存在则跳过）
	 * 2. INSERT 用户消息记录
	 * <p>
	 * 如果用户消息插入失败，会话创建也会回滚（INSERT IGNORE 的行不会持久化）。
	 *
	 * @param conversationId 会话 ID
	 * @param question       用户问题（同时作为会话标题）
	 * @param mode           问答模式
	 * @return 用户消息 ID（UUID）
	 */
	@Transactional
	public String initConversationAndSaveUserMessage(String conversationId, String question, String mode) {
		ensureConversation(conversationId, question, mode);
		return saveUserMessage(conversationId, question, mode);
	}

	/**
	 * 【事务】保存助手消息并更新会话统计，确保两个操作原子提交。
	 * <p>
	 * 在同一事务中执行：
	 * 1. INSERT 助手消息记录（含 token 用量和耗时）
	 * 2. UPDATE 会话的 message_count 和 total_tokens（聚合计算）
	 * <p>
	 * 统计更新失败仅记录日志，不会回滚已保存的助手消息（通过内部 try-catch 隔离）。
	 *
	 * @param conversationId   会话 ID
	 * @param content          LLM 生成的回答文本
	 * @param mode             问答模式
	 * @param model            使用的模型名称
	 * @param promptTokens     输入 token 数
	 * @param completionTokens 输出 token 数
	 * @param totalTokens      总 token 数
	 * @param toolCalls        工具调用记录（JSON）
	 * @param toolCallsCount   工具调用次数
	 * @param ragDocCount      RAG 检索文档数
	 * @param durationMs       响应耗时（毫秒）
	 * @return 助手消息 ID（UUID）
	 */
	@Transactional
	public String saveAssistantMessageAndUpdateStats(String conversationId, String content, String mode,
			String model, int promptTokens, int completionTokens, int totalTokens,
			String toolCalls, int toolCallsCount, int ragDocCount, Integer durationMs) {
		String messageId = saveAssistantMessage(conversationId, content, mode,
			model, promptTokens, completionTokens, totalTokens,
			toolCalls, toolCallsCount, ragDocCount, durationMs);
		// 统计更新为非关键操作，失败不回滚事务
		try {
			updateConversationStats(conversationId);
		}
		catch (Exception e) {
			log.warn("更新会话统计失败（消息已保存）: conversationId={}, error={}", conversationId, e.getMessage());
		}
		return messageId;
	}

	// ==================== 基础操作方法 ====================

	/**
	 * 确保会话存在，不存在则创建（幂等操作）。
	 * 使用 INSERT IGNORE 避免并发场景下的重复创建。
	 *
	 * @param conversationId 会话 ID（UUID，由前端或 Controller 生成）
	 * @param title          会话标题（取用户首条消息，截取前 100 字符）
	 * @param mode           问答模式：auto / data / knowledge
	 */
	public void ensureConversation(String conversationId, String title, String mode) {
		String entCode = TenantContext.requireEntCode();
		String userId = TenantContext.getUserIdOrDefault();
		String truncatedTitle = title.length() > 100 ? title.substring(0, 100) + "..." : title;
		erp.update(
			"INSERT IGNORE INTO a_chat_conversation " +
			"(conversation_id, ent_code, user_id, title, mode) VALUES (?, ?, ?, ?, ?)",
			conversationId, entCode, userId, truncatedTitle, mode);
	}

	/**
	 * 保存用户消息。
	 *
	 * @param conversationId 所属会话 ID
	 * @param content        用户输入的问题文本
	 * @param mode           问答模式
	 * @return 生成的消息 ID（UUID）
	 */
	public String saveUserMessage(String conversationId, String content, String mode) {
		return saveMessage(conversationId, "user", content, mode,
			null, 0, 0, 0, null, 0, 0, null, "success", null);
	}

	/**
	 * 保存 LLM 助手回复消息（含 token 用量和耗时统计）。
	 *
	 * @param conversationId  所属会话 ID
	 * @param content         LLM 生成的回答文本
	 * @param mode            问答模式
	 * @param model           使用的模型名称（如 deepseek-chat）
	 * @param promptTokens    提示词消耗的 token 数
	 * @param completionTokens 生成回答消耗的 token 数
	 * @param totalTokens     总 token 数
	 * @param toolCalls       工具调用记录（JSON 数组字符串）
	 * @param toolCallsCount  工具调用次数
	 * @param ragDocCount     RAG 检索的文档片段数
	 * @param durationMs      LLM 响应耗时（毫秒）
	 * @return 生成的消息 ID（UUID）
	 */
	public String saveAssistantMessage(String conversationId, String content, String mode,
			String model, int promptTokens, int completionTokens, int totalTokens,
			String toolCalls, int toolCallsCount, int ragDocCount, Integer durationMs) {
		return saveMessage(conversationId, "assistant", content, mode,
			model, promptTokens, completionTokens, totalTokens,
			toolCalls, toolCallsCount, ragDocCount, durationMs, "success", null);
	}

	/**
	 * 保存失败消息（LLM 调用异常时记录错误信息）。
	 *
	 * @param conversationId 所属会话 ID
	 * @param mode           问答模式
	 * @param model          模型名称
	 * @param durationMs     耗时（毫秒）
	 * @param errorMessage   错误描述
	 * @return 生成的消息 ID（UUID）
	 */
	public String saveErrorMessage(String conversationId, String mode, String model,
			Integer durationMs, String errorMessage) {
		return saveMessage(conversationId, "assistant", null, mode,
			model, 0, 0, 0, null, 0, 0, durationMs, "error", errorMessage);
	}

	/**
	 * 更新会话统计信息（消息总数 + 累计 token 数）。
	 * 通过子查询从消息表聚合计算。
	 *
	 * @param conversationId 会话 ID
	 */
	public void updateConversationStats(String conversationId) {
		erp.update(
			"UPDATE a_chat_conversation SET " +
			"message_count = (SELECT COUNT(*) FROM a_chat_message WHERE conversation_id = ?), " +
			"total_tokens = (SELECT COALESCE(SUM(total_tokens), 0) FROM a_chat_message WHERE conversation_id = ?), " +
			"updated_at = NOW() " +
			"WHERE conversation_id = ?",
			conversationId, conversationId, conversationId);
	}

	// ==================== 查询方法 ====================

	/**
	 * 查询当前用户的会话列表（分页，按最后更新时间倒序）。
	 * 自动按 ent_code + user_id 过滤，排除已删除的会话。
	 *
	 * @param page 页码（从 0 开始）
	 * @param size 每页数量
	 * @return 会话摘要列表
	 */
	public List<ConversationVO.ConversationItemResponse> getConversations(int page, int size) {
		String entCode = TenantContext.requireEntCode();
		String userId = TenantContext.getUserIdOrDefault();
		return erp.query(
			"SELECT conversation_id, title, mode, message_count, total_tokens, status, created_at, updated_at " +
			"FROM a_chat_conversation " +
			"WHERE ent_code = ? AND user_id = ? AND status != 'deleted' " +
			"ORDER BY updated_at DESC LIMIT ? OFFSET ?",
			(rs, rowNum) -> new ConversationVO.ConversationItemResponse(
				rs.getString("conversation_id"),
				rs.getString("title"),
				rs.getString("mode"),
				rs.getInt("message_count"),
				rs.getInt("total_tokens"),
				rs.getString("status"),
				rs.getString("created_at"),
				rs.getString("updated_at")),
			entCode, userId, size, page * size);
	}

	/**
	 * 查询指定会话下的所有消息（按创建时间正序）。
	 * 自动按 ent_code 过滤，确保租户隔离。
	 *
	 * @param conversationId 会话 ID
	 * @return 消息详情列表
	 */
	public List<ConversationVO.ChatMessageItemResponse> getMessages(String conversationId) {
		String entCode = TenantContext.requireEntCode();
		return erp.query(
			"SELECT message_id, role, content, mode, model, " +
			"prompt_tokens, completion_tokens, total_tokens, " +
			"tool_calls, tool_calls_count, rag_doc_count, duration_ms, status, error_message, created_at " +
			"FROM a_chat_message " +
			"WHERE conversation_id = ? AND ent_code = ? " +
			"ORDER BY created_at ASC",
			(rs, rowNum) -> new ConversationVO.ChatMessageItemResponse(
				rs.getString("message_id"),
				rs.getString("role"),
				rs.getString("content"),
				rs.getString("mode"),
				rs.getString("model"),
				rs.getInt("prompt_tokens"),
				rs.getInt("completion_tokens"),
				rs.getInt("total_tokens"),
				rs.getString("tool_calls"),
				rs.getInt("tool_calls_count"),
				rs.getInt("rag_doc_count"),
				rs.getObject("duration_ms", Integer.class),
				rs.getString("status"),
				rs.getString("error_message"),
				rs.getString("created_at")),
			conversationId, entCode);
	}

	/**
	 * 归档会话（软删除，将状态设为 deleted）。
	 *
	 * @param conversationId 会话 ID
	 */
	public void archiveConversation(String conversationId) {
		String entCode = TenantContext.requireEntCode();
		erp.update(
			"UPDATE a_chat_conversation SET status = 'deleted', updated_at = NOW() " +
			"WHERE conversation_id = ? AND ent_code = ?",
			conversationId, entCode);
	}

	// ==================== 私有方法 ====================

	/**
	 * 内部方法：插入一条消息记录到 a_chat_message 表。
	 * 自动生成 UUID 作为 messageId，自动填充 ent_code 和 user_id。
	 */
	private String saveMessage(String conversationId, String role, String content, String mode,
			String model, int promptTokens, int completionTokens, int totalTokens,
			String toolCalls, int toolCallsCount, int ragDocCount,
			Integer durationMs, String status, String errorMessage) {
		String messageId = UUID.randomUUID().toString();
		String entCode = TenantContext.requireEntCode();
		String userId = TenantContext.getUserIdOrDefault();
		erp.update(
			"INSERT INTO a_chat_message " +
			"(message_id, conversation_id, ent_code, user_id, role, content, mode, model, " +
			"prompt_tokens, completion_tokens, total_tokens, " +
			"tool_calls, tool_calls_count, rag_doc_count, duration_ms, status, error_message) " +
			"VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
			messageId, conversationId, entCode, userId, role, content, mode, model,
			promptTokens, completionTokens, totalTokens,
			toolCalls, toolCallsCount, ragDocCount, durationMs, status, errorMessage);
		return messageId;
	}

}
