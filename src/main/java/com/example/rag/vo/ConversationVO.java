package com.example.rag.vo;

import java.util.List;

/**
 * 对话记录模块 VO —— {@link com.example.rag.controller.ConversationController} 的入参和出参定义。
 * <p>
 * 包含会话列表查询、会话消息查询、会话删除的请求/响应对象。
 */
public final class ConversationVO {

	private ConversationVO() {
	}

	// ==================== Request ====================

	/**
	 * 查询会话列表入参。
	 *
	 * @param page 页码（从 0 开始），默认 0
	 * @param size 每页数量，默认 20
	 */
	public record ListConversationsRequest(Integer page, Integer size) {
		public ListConversationsRequest {
			page = (page == null || page < 0) ? 0 : page;
			size = (size == null || size <= 0) ? 20 : size;
		}
	}

	// ==================== Response ====================

	/**
	 * 会话列表中的单个会话摘要。
	 *
	 * @param conversationId 会话唯一标识（UUID）
	 * @param title          会话标题（取自首条消息摘要）
	 * @param mode           问答模式：auto / data / knowledge
	 * @param messageCount   会话中的消息总数
	 * @param totalTokens    会话累计消耗的 token 数
	 * @param status         会话状态：active / archived / deleted
	 * @param createdAt      创建时间
	 * @param updatedAt      最后更新时间
	 */
	public record ConversationItemResponse(
			String conversationId, String title, String mode,
			int messageCount, int totalTokens, String status,
			String createdAt, String updatedAt) {
	}

	/**
	 * 会话中的单条消息详情。
	 *
	 * @param messageId        消息唯一标识（UUID）
	 * @param role             角色：user / assistant / system
	 * @param content          消息文本内容
	 * @param mode             问答模式
	 * @param model            使用的 LLM 模型名称（如 deepseek-chat）
	 * @param promptTokens     提示词 token 数
	 * @param completionTokens 生成回答 token 数
	 * @param totalTokens      总 token 数
	 * @param toolCalls        工具调用记录（JSON 数组字符串）
	 * @param toolCallsCount   工具调用次数
	 * @param chart            助手图表数据，无图表时为空
	 * @param ragDocCount      RAG 检索的文档片段数
	 * @param durationMs       LLM 响应耗时（毫秒）
	 * @param status           消息状态：success / cancelled / error / timeout
	 * @param errorMessage     错误信息（仅失败时有值）
	 * @param createdAt        创建时间
	 */
	public record ChatMessageItemResponse(
			String messageId, String role, String content, String mode, String model,
			int promptTokens, int completionTokens, int totalTokens,
			String toolCalls, int toolCallsCount, ChartVO.ChartSpec chart, int ragDocCount,
			Integer durationMs, String status, String errorMessage, String createdAt) {
	}

	/**
	 * 数据库查询使用的消息记录。
	 *
	 * @param messageId        消息唯一标识（UUID）
	 * @param role             角色：user / assistant / system
	 * @param content          消息文本内容
	 * @param mode             问答模式
	 * @param model            使用的 LLM 模型名称
	 * @param promptTokens     提示词 token 数
	 * @param completionTokens 生成回答 token 数
	 * @param totalTokens      总 token 数
	 * @param toolCalls        工具调用记录（JSON 数组字符串）
	 * @param toolCallsCount   工具调用次数
	 * @param chartSpec        助手图表数据 JSON
	 * @param ragDocCount      RAG 检索的文档片段数
	 * @param durationMs       LLM 响应耗时（毫秒）
	 * @param status           消息状态
	 * @param errorMessage     错误信息
	 * @param createdAt        创建时间
	 */
	public record ChatMessageRecord(
			String messageId, String role, String content, String mode, String model,
			int promptTokens, int completionTokens, int totalTokens,
			String toolCalls, int toolCallsCount, String chartSpec, int ragDocCount,
			Integer durationMs, String status, String errorMessage, String createdAt) {
	}

	/**
	 * 会话列表出参（分页）。
	 *
	 * @param page 当前页码
	 * @param size 每页数量
	 * @param data 会话摘要列表
	 */
	public record ListConversationsResponse(int page, int size, List<ConversationItemResponse> data) {
	}

	/**
	 * 指定会话的消息列表出参。
	 *
	 * @param conversationId 会话 ID
	 * @param messages       该会话下的所有消息，按时间正序排列
	 */
	public record ConversationMessagesResponse(String conversationId, List<ChatMessageItemResponse> messages) {
	}

	/**
	 * 删除会话出参。
	 *
	 * @param conversationId 被删除的会话 ID
	 */
	public record DeleteConversationResponse(String conversationId) {
	}

}
