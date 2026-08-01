package com.example.rag.conversation;

import java.util.List;
import java.util.UUID;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.rag.chat.chart.protocol.ChartSpecCodec;
import com.example.rag.chat.output.AssistantAnswerSanitizer;
import com.example.rag.config.TenantContext;
import com.example.rag.dao.entity.ChatConversationEntity;
import com.example.rag.dao.entity.ChatMessageEntity;
import com.example.rag.dao.mapper.ChatConversationMapper;
import com.example.rag.dao.mapper.ChatMessageMapper;
import com.example.rag.vo.ConversationVO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

	/** 对话会话 Mapper。 */
	private final ChatConversationMapper conversationMapper;

	/** 对话消息 Mapper。 */
	private final ChatMessageMapper messageMapper;

	/** 图表协议编解码器。 */
	private final ChartSpecCodec chartSpecCodec;

	/** 助手最终答案净化器。 */
	private final AssistantAnswerSanitizer answerSanitizer;

	public ChatHistoryService(ChatConversationMapper conversationMapper, ChatMessageMapper messageMapper,
			ChartSpecCodec chartSpecCodec, AssistantAnswerSanitizer answerSanitizer) {
		this.conversationMapper = conversationMapper;
		this.messageMapper = messageMapper;
		this.chartSpecCodec = chartSpecCodec;
		this.answerSanitizer = answerSanitizer;
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
	@Transactional(transactionManager = "erpTransactionManager")
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
	 * @param chartSpec        助手图表数据 JSON
	 * @param ragDocCount      RAG 检索文档数
	 * @param durationMs       响应耗时（毫秒）
	 * @return 助手消息 ID（UUID）
	 */
	@Transactional(transactionManager = "erpTransactionManager")
	public String saveAssistantMessageAndUpdateStats(String conversationId, String content, String mode,
			String model, int promptTokens, int completionTokens, int totalTokens,
			String toolCalls, int toolCallsCount, String chartSpec, int ragDocCount, Integer durationMs) {
		return saveAssistantMessageAndUpdateStats(conversationId, content, mode, model,
			promptTokens, completionTokens, totalTokens, toolCalls, toolCallsCount, chartSpec, ragDocCount,
			durationMs, "success", null);
	}

	/**
	 * 【事务】保存助手消息并更新会话统计（支持自定义状态）。
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
	 * @param chartSpec        助手图表数据 JSON
	 * @param ragDocCount      RAG 检索文档数
	 * @param durationMs       响应耗时（毫秒）
	 * @param status           助手消息状态：success / cancelled / error
	 * @param errorMessage     错误信息（仅失败时返回）
	 * @return 助手消息 ID（UUID）
	 */
	@Transactional(transactionManager = "erpTransactionManager")
	public String saveAssistantMessageAndUpdateStats(String conversationId, String content, String mode,
			String model, int promptTokens, int completionTokens, int totalTokens,
			String toolCalls, int toolCallsCount, String chartSpec, int ragDocCount, Integer durationMs,
			String status, String errorMessage) {
		String messageId = saveAssistantMessage(conversationId, content, mode,
			model, promptTokens, completionTokens, totalTokens,
			toolCalls, toolCallsCount, chartSpec, ragDocCount, durationMs, status, errorMessage);
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
		conversationMapper.insertIgnore(conversationId, entCode, userId, truncatedTitle, mode);
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
			null, 0, 0, 0, null, 0, null, 0, null, "success", null);
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
	 * @param chartSpec       助手图表数据 JSON
	 * @param ragDocCount     RAG 检索的文档片段数
	 * @param durationMs      LLM 响应耗时（毫秒）
	 * @return 生成的消息 ID（UUID）
	 */
	public String saveAssistantMessage(String conversationId, String content, String mode,
			String model, int promptTokens, int completionTokens, int totalTokens,
			String toolCalls, int toolCallsCount, String chartSpec, int ragDocCount, Integer durationMs) {
		return saveAssistantMessage(conversationId, content, mode, model, promptTokens,
			completionTokens, totalTokens, toolCalls, toolCallsCount, chartSpec, ragDocCount, durationMs,
			"success", null);
	}

	/**
	 * 保存 LLM 助手回复消息（支持自定义状态）。
	 *
	 * @param conversationId   所属会话 ID
	 * @param content          LLM 生成的回答文本
	 * @param mode             问答模式
	 * @param model            使用的模型名称（如 deepseek-chat）
	 * @param promptTokens     提示词消耗的 token 数
	 * @param completionTokens 生成回答消耗的 token 数
	 * @param totalTokens      总 token 数
	 * @param toolCalls        工具调用记录（JSON 数组字符串）
	 * @param toolCallsCount   工具调用次数
	 * @param chartSpec        助手图表数据 JSON
	 * @param ragDocCount      RAG 检索的文档片段数
	 * @param durationMs       LLM 响应耗时（毫秒）
	 * @param status           消息状态：success / cancelled / error
	 * @param errorMessage     错误信息（仅失败时有值）
	 * @return 生成的消息 ID（UUID）
	 */
	public String saveAssistantMessage(String conversationId, String content, String mode,
			String model, int promptTokens, int completionTokens, int totalTokens,
			String toolCalls, int toolCallsCount, String chartSpec, int ragDocCount, Integer durationMs,
			String status, String errorMessage) {
		return saveMessage(conversationId, "assistant", content, mode,
			model, promptTokens, completionTokens, totalTokens,
			toolCalls, toolCallsCount, chartSpec, ragDocCount, durationMs, status, errorMessage);
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
			model, 0, 0, 0, null, 0, null, 0, durationMs, "error", errorMessage);
	}

	/**
	 * 更新会话统计信息（消息总数 + 累计 token 数）。
	 * 通过子查询从消息表聚合计算。
	 *
	 * @param conversationId 会话 ID
	 */
	public void updateConversationStats(String conversationId) {
		conversationMapper.updateStats(conversationId);
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
		String userId = TenantContext.getUserIdOrDefault();
		return conversationMapper.selectConversationItems(userId, size, page * size);
	}

	/**
	 * 查询指定会话下的所有消息（按创建时间正序）。
	 * 自动按 ent_code + user_id 过滤，确保租户和用户隔离。
	 *
	 * @param conversationId 会话 ID
	 * @return 消息详情列表
	 */
	public List<ConversationVO.ChatMessageItemResponse> getMessages(String conversationId) {
		// 历史消息查询与续聊共用会话所有权校验，避免同租户用户越权读取。
		requireConversationActive(conversationId, true);
		return messageMapper.selectMessageItems(conversationId).stream()
			.map(this::toMessageItem)
			.toList();
	}

	/**
	 * 归档会话（软删除，将状态设为 deleted）。
	 *
	 * @param conversationId 会话 ID
	 */
	public void archiveConversation(String conversationId) {
		// 软删除前校验当前用户拥有目标会话，避免同租户用户越权归档。
		requireConversationActive(conversationId, true);
		ChatConversationEntity entity = new ChatConversationEntity();
		entity.setStatus("deleted");
		conversationMapper.update(entity, new LambdaUpdateWrapper<ChatConversationEntity>()
			.eq(ChatConversationEntity::getConversationId, conversationId));
	}

	// ==================== 防御性校验 ====================

	/**
	 * 校验目标会话是否处于「可继续」状态。
	 * <p>
	 * 防御层：当调用方传入一个明确的 {@code conversation_id} 时，必须能在当前租户下查到该会话，
	 * 且会话不能是 status='deleted'。查不到时抛出「会话不存在，不可继续」；已删除时抛出
	 * 「会话已删除，不可继续」。两类异常均由 {@link com.example.rag.config.GlobalExceptionHandler}
	 * 统一返回 {@code BIZ_ERROR}。调用方应在写入用户消息或调用 LLM 之前调用本方法，避免在非法
	 * 会话上沉淀「幽灵消息」（消息表中存在但会话不可见）。
	 * <p>
	 * 新会话场景：调用方未传 {@code conversation_id} 时，Controller 会生成新的 ID，并以
	 * {@code requireExisting=false} 调用本方法；此时查不到记录属于正常新建路径。
	 * <p>
	 * 跨租户访问场景：SELECT 已带 {@code ent_code} 过滤；若调用方传入其他租户的 ID，会按
	 * 「会话不存在，不可继续」拒绝，不会泄漏其他租户数据。
	 *
	 * @param conversationId 待校验的会话 ID
	 * @param requireExisting 是否要求目标会话必须已存在；续聊为 true，新建为 false
	 * @throws IllegalStateException 会话不存在或已删除时抛出（错误码 {@code BIZ_ERROR}）
	 */
	public void requireConversationActive(String conversationId, boolean requireExisting) {
		TenantContext.requireEntCode();
		String userId = TenantContext.getUserIdOrDefault();
		// 会话状态查询同时限定当前用户，其他用户的会话统一表现为不存在。
		List<String> existingStatus = conversationMapper.selectStatus(conversationId, userId);
		if (existingStatus.isEmpty()) {
			if (requireExisting) {
				throw new IllegalStateException("会话不存在，不可继续");
			}
			return;
		}
		if ("deleted".equals(existingStatus.get(0))) {
			throw new IllegalStateException("会话已删除，不可继续");
		}
	}

	// ==================== 私有方法 ====================

	/**
	 * 内部方法：插入一条消息记录到 a_chat_message 表。
	 * 自动生成 UUID 作为 messageId，自动填充 ent_code 和 user_id。
	 */
	private String saveMessage(String conversationId, String role, String content, String mode,
			String model, int promptTokens, int completionTokens, int totalTokens,
			String toolCalls, int toolCallsCount, String chartSpec, int ragDocCount,
			Integer durationMs, String status, String errorMessage) {
		String messageId = UUID.randomUUID().toString();
		String entCode = TenantContext.requireEntCode();
		String userId = TenantContext.getUserIdOrDefault();
		ChatMessageEntity entity = new ChatMessageEntity();
		entity.setMessageId(messageId);
		entity.setConversationId(conversationId);
		entity.setEntCode(entCode);
		entity.setUserId(userId);
		entity.setRole(role);
		entity.setContent(content);
		entity.setMode(mode);
		entity.setModel(model);
		entity.setPromptTokens(promptTokens);
		entity.setCompletionTokens(completionTokens);
		entity.setTotalTokens(totalTokens);
		entity.setToolCalls(toolCalls);
		entity.setToolCallsCount(toolCallsCount);
		entity.setChartSpec(chartSpec);
		entity.setRagDocCount(ragDocCount);
		entity.setDurationMs(durationMs);
		entity.setStatus(status);
		entity.setErrorMessage(errorMessage);
		messageMapper.insert(entity);
		return messageId;
	}

	/**
	 * 将数据库消息记录转换为接口返回结构。
	 *
	 * @param record 数据库消息记录
	 * @return 接口消息结构
	 */
	private ConversationVO.ChatMessageItemResponse toMessageItem(ConversationVO.ChatMessageRecord record) {
		com.example.rag.vo.ChartVO.ChartSpec chart = null;
		try {
			chart = chartSpecCodec.decode(record.chartSpec());
		}
		catch (IllegalArgumentException ex) {
			log.warn("解析历史消息图表失败: messageId={}, error={}", record.messageId(), ex.getMessage());
		}
		String content = "assistant".equals(record.role())
			? this.answerSanitizer.sanitize(record.content()) : record.content();
		return new ConversationVO.ChatMessageItemResponse(
			record.messageId(), record.role(), content, record.mode(), record.model(),
			record.promptTokens(), record.completionTokens(), record.totalTokens(),
			record.toolCalls(), record.toolCallsCount(), chart, record.ragDocCount(),
			record.durationMs(), record.status(), record.errorMessage(), record.createdAt());
	}

}
