package com.example.rag.chat.lifecycle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.example.rag.billing.BillingService;
import com.example.rag.chat.chart.capture.ToolResultRecorder;
import com.example.rag.chat.chart.protocol.ChartSpecCodec;
import com.example.rag.chat.chart.selection.ChartSelectionService;
import com.example.rag.chat.dto.ChatAnswerResult;
import com.example.rag.chat.dto.ChatStreamFrame;
import com.example.rag.chat.dto.SavedAssistantMessage;
import com.example.rag.chat.output.AssistantAnswerSanitizer;
import com.example.rag.config.TenantContext;
import com.example.rag.config.TenantContextAccessor;
import com.example.rag.conversation.ChatHistoryService;
import com.example.rag.tool.trace.ToolCallLogService;
import com.example.rag.tool.trace.ToolCallRecorder;
import com.example.rag.vo.ChartVO;
import com.example.rag.vo.ChatVO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;

/**
 * 智能助手问答生命周期服务，统一处理消息持久化、计费、Tool 记录和流式收口。
 */
@Service
public class AssistantLifecycleService {

	private static final Logger log = LoggerFactory.getLogger(AssistantLifecycleService.class);

	/** 对话记录服务，负责会话和消息持久化。 */
	private final ChatHistoryService chatHistoryService;

	/** 计费服务，负责配额校验和 Token 扣费。 */
	private final BillingService billingService;

	/** Tool 调用聚合记录器。 */
	private final ToolCallRecorder toolCallRecorder;

	/** Tool 调用流水服务。 */
	private final ToolCallLogService toolCallLogService;

	/** 业务 Tool 结果记录器。 */
	private final ToolResultRecorder toolResultRecorder;

	/** 图表协议编解码器。 */
	private final ChartSpecCodec chartSpecCodec;

	/** 助手最终答案净化器。 */
	private final AssistantAnswerSanitizer answerSanitizer;

	/** 图表类型和标题兜底选择服务。 */
	private final ChartSelectionService chartSelectionService;

	/**
	 * 创建智能助手问答生命周期服务。
	 *
	 * @param chatHistoryService 对话记录服务
	 * @param billingService     计费服务
	 * @param toolCallRecorder   Tool 调用聚合记录器
	 * @param toolCallLogService Tool 调用流水服务
	 * @param toolResultRecorder 业务 Tool 结果记录器
	 * @param chartSpecCodec     图表协议编解码器
	 * @param answerSanitizer    助手最终答案净化器
	 * @param chartSelectionService 图表类型和标题兜底选择服务
	 */
	public AssistantLifecycleService(ChatHistoryService chatHistoryService,
			BillingService billingService,
			ToolCallRecorder toolCallRecorder,
			ToolCallLogService toolCallLogService,
			ToolResultRecorder toolResultRecorder,
			ChartSpecCodec chartSpecCodec,
			AssistantAnswerSanitizer answerSanitizer,
			ChartSelectionService chartSelectionService) {
		this.chatHistoryService = chatHistoryService;
		this.billingService = billingService;
		this.toolCallRecorder = toolCallRecorder;
		this.toolCallLogService = toolCallLogService;
		this.toolResultRecorder = toolResultRecorder;
		this.chartSpecCodec = chartSpecCodec;
		this.answerSanitizer = answerSanitizer;
		this.chartSelectionService = chartSelectionService;
	}

	/**
	 * 在 LLM 调用前校验会话、保存用户消息并检查计费配额。
	 *
	 * @param conversationId              会话 ID
	 * @param question                    用户问题
	 * @param mode                        问答模式
	 * @param requireExistingConversation 是否要求会话已存在
	 */
	public void prepareConversation(String conversationId, String question, String mode,
			boolean requireExistingConversation) {
		// 防御性校验必须在写入前完成，避免已删除会话产生幽灵消息。
		this.chatHistoryService.requireConversationActive(conversationId, requireExistingConversation);
		try {
			this.chatHistoryService.initConversationAndSaveUserMessage(conversationId, question, mode);
		}
		catch (IllegalStateException ex) {
			// 业务拒绝必须继续向上传播，由统一异常处理返回业务错误。
			throw ex;
		}
		catch (Exception ex) {
			log.warn("保存用户消息失败: {}", ex.getMessage());
		}
		this.billingService.checkQuota();
	}

	/**
	 * 创建单次问答的 Tool 链路 ID。
	 *
	 * @return Tool 链路 ID
	 */
	public String createTraceId() {
		return this.toolCallRecorder.createTraceId();
	}

	/**
	 * 构建传递给 ToolCallback 的链路上下文。
	 *
	 * @param traceId        问答链路 ID
	 * @param conversationId 会话 ID
	 * @param mode           问答模式
	 * @param modelName      使用模型
	 * @return Tool 上下文
	 */
	public Map<String, Object> buildToolContext(String traceId, String conversationId,
			String mode, String modelName) {
		return Map.of(
			"traceId", traceId,
			"conversationId", conversationId,
			"mode", mode,
			"model", modelName,
			"entCode", TenantContext.requireEntCode(),
			"userId", TenantContext.getUserIdOrDefault());
	}

	/**
	 * 完成非流式回答的文本提取、消息保存、图表收口和计费扣除。
	 *
	 * @param question       用户原始问题
	 * @param conversationId 会话 ID
	 * @param mode           问答模式
	 * @param modelId        模型配置 ID
	 * @param modelName      模型名称
	 * @param response       模型响应
	 * @param durationMs     响应耗时
	 * @param traceId        问答链路 ID
	 * @return 文本与可空图表
	 */
	public ChatAnswerResult finishNonStreaming(String question, String conversationId, String mode,
			String modelId, String modelName, ChatResponse response, long durationMs, String traceId) {
		String content = this.answerSanitizer.sanitize(this.extractContent(response));
		ChatResponse selectionResponse = this.chartSelectionService.ensureChart(
			question, content, modelId, modelName, traceId, conversationId, mode);
		int[] tokens = this.mergeTokenUsage(response, selectionResponse);
		String toolCalls = this.toolCallRecorder.getToolCallsJson(traceId);
		int toolCallsCount = this.toolCallRecorder.getToolCallCount(traceId);
		ChartVO.ChartSpec chart = this.resolveChart(
			traceId, TenantContext.requireEntCode(), conversationId);
		// 消息事务与计费事务保持独立，计费失败不回滚已保存消息。
		SavedAssistantMessage savedAssistant = this.saveAssistantWithChartFallback(
			conversationId, content, mode, modelName, tokens,
			toolCalls, toolCallsCount, (int) durationMs, "success", null, chart, traceId);
		try {
			this.billingService.deductForTokenUsage(
				tokens[2], tokens[0], tokens[1], modelName, conversationId);
		}
		catch (Exception ex) {
			log.warn("计费扣除失败: {}", ex.getMessage());
		}
		return new ChatAnswerResult(content, savedAssistant.chart());
	}

	/**
	 * 将模型响应流转换为类型化事件，并在成功、异常和取消路径执行单次收口。
	 *
	 * @param responseFlux   模型响应流
	 * @param question       用户原始问题
	 * @param conversationId 会话 ID
	 * @param mode           问答模式
	 * @param modelId        模型配置 ID
	 * @param modelName      模型名称
	 * @param traceId        问答链路 ID
	 * @return 类型化事件流
	 */
	public Flux<ChatStreamFrame> recordStream(Flux<ChatResponse> responseFlux, String question,
			String conversationId, String mode, String modelId, String modelName, String traceId) {
		long startTime = System.currentTimeMillis();
		AssistantAnswerSanitizer.StreamSession streamSession = this.answerSanitizer.openStream();
		AtomicReference<Usage> usageRef = new AtomicReference<>();
		AtomicBoolean finalized = new AtomicBoolean();

		// 在 Servlet 线程捕获租户上下文，供 Reactor 链路与 Tool Calling 线程恢复。
		String entCode = TenantContext.getEntCode();
		String userId = TenantContext.getUserId();

		Flux<ChatStreamFrame> deltaFrames = responseFlux
			.doOnNext(response -> {
				// Token 用量通常仅在最后一个分片中出现，保留最后一次有效值。
				Usage usage = response.getMetadata().getUsage();
				if (usage != null && usage.getTotalTokens() != null && usage.getTotalTokens() > 0) {
					usageRef.set(usage);
				}
			})
			.mapNotNull(response -> {
				var result = response.getResult();
				return result != null && result.getOutput() != null
					? result.getOutput().getText() : null;
			})
			.map(streamSession::accept)
			.filter(text -> !text.isEmpty())
			.publishOn(Schedulers.boundedElastic())
			.map(text -> new ChatStreamFrame("delta", new ChatVO.StreamDelta(text)));

		return deltaFrames
			.concatWith(Flux.defer(() -> Flux.fromIterable(this.finalizeStream(
				finalized, SignalType.ON_COMPLETE, null, question, conversationId, mode, modelId, modelName,
				traceId, entCode, userId, startTime, streamSession, usageRef))))
			.onErrorResume(error -> Flux.fromIterable(this.finalizeStream(
				finalized, SignalType.ON_ERROR, error, question, conversationId, mode, modelId, modelName,
				traceId, entCode, userId, startTime, streamSession, usageRef)))
			.doFinally(signal -> {
				if (signal == SignalType.CANCEL) {
					this.finalizeStream(finalized, SignalType.CANCEL, null,
						question, conversationId, mode, modelId, modelName, traceId, entCode, userId,
						startTime, streamSession, usageRef);
				}
			})
			// 租户信息写入 Reactor Context 后可由现有上下文访问器恢复到异步线程。
			.contextWrite(context -> context.put(
				TenantContextAccessor.KEY,
				new TenantContextAccessor.TenantInfo(entCode, userId)));
	}

	/**
	 * 清理非流式问答使用的 Tool 聚合与图表暂存数据。
	 *
	 * @param traceId 问答链路 ID
	 */
	public void clearTrace(String traceId) {
		this.toolCallRecorder.clear(traceId);
		this.toolResultRecorder.clear(traceId);
	}

	/**
	 * 从模型响应元数据中提取 Token 用量。
	 *
	 * @param response 模型响应
	 * @return prompt、completion、total Token 数组
	 */
	private int[] extractTokenUsage(ChatResponse response) {
		return this.extractTokenUsage(response.getMetadata().getUsage());
	}

	/**
	 * 从 Usage 对象中安全提取 Token 用量。
	 *
	 * @param usage Token 用量
	 * @return prompt、completion、total Token 数组
	 */
	private int[] extractTokenUsage(Usage usage) {
		if (usage == null) {
			return new int[]{0, 0, 0};
		}
		return new int[]{
			safeUnbox(usage.getPromptTokens()),
			safeUnbox(usage.getCompletionTokens()),
			safeUnbox(usage.getTotalTokens())
		};
	}

	/**
	 * 安全拆箱可空整数。
	 *
	 * @param value 可空整数
	 * @return 非空整数值
	 */
	private static int safeUnbox(Integer value) {
		return value != null ? value : 0;
	}

	/**
	 * 从模型响应中安全提取回答文本。
	 *
	 * @param response 模型响应
	 * @return 回答文本
	 */
	private String extractContent(ChatResponse response) {
		if (response == null || response.getResult() == null) {
			return "";
		}
		return response.getResult().getOutput().getText();
	}

	/**
	 * 保存带可空图表的助手消息；带图表失败时只降级重试一次文本消息。
	 *
	 * @param conversationId 会话 ID
	 * @param content        回答文本
	 * @param mode           问答模式
	 * @param modelName      模型名称
	 * @param tokens         Token 用量
	 * @param toolCalls      业务 Tool 调用摘要
	 * @param toolCallsCount 业务 Tool 调用次数
	 * @param durationMs     响应耗时
	 * @param status         消息状态
	 * @param errorMessage   错误信息
	 * @param chart          可空图表
	 * @param traceId        问答链路 ID
	 * @return 保存结果
	 */
	private SavedAssistantMessage saveAssistantWithChartFallback(
			String conversationId, String content, String mode, String modelName, int[] tokens,
			String toolCalls, int toolCallsCount, int durationMs, String status, String errorMessage,
			ChartVO.ChartSpec chart, String traceId) {
		String chartJson = null;
		ChartVO.ChartSpec persistedChart = chart;
		if (chart != null) {
			try {
				chartJson = this.chartSpecCodec.encode(chart);
			}
			catch (Exception ex) {
				persistedChart = null;
				log.warn("图表持久化前校验失败，已降级为文本: traceId={}, conversationId={}, error={}",
					traceId, conversationId, ex.getMessage());
			}
		}
		try {
			String messageId = this.chatHistoryService.saveAssistantMessageAndUpdateStats(
				conversationId, content, mode, modelName,
				tokens[0], tokens[1], tokens[2], toolCalls, toolCallsCount,
				chartJson, 0, durationMs, status, errorMessage);
			this.attachToolCallLogs(traceId, messageId);
			return new SavedAssistantMessage(messageId, persistedChart);
		}
		catch (Exception ex) {
			if (chartJson == null) {
				log.warn("保存助手消息失败: {}", ex.getMessage());
				return new SavedAssistantMessage(null, null);
			}
			log.warn("图表消息保存失败，已降级重试文本消息: traceId={}, conversationId={}, error={}",
				traceId, conversationId, ex.getMessage());
			try {
				String messageId = this.chatHistoryService.saveAssistantMessageAndUpdateStats(
					conversationId, content, mode, modelName,
					tokens[0], tokens[1], tokens[2], toolCalls, toolCallsCount,
					null, 0, durationMs, status, errorMessage);
				this.attachToolCallLogs(traceId, messageId);
				return new SavedAssistantMessage(messageId, null);
			}
			catch (Exception retryEx) {
				log.warn("图表降级后的文本消息保存失败: {}", retryEx.getMessage());
				return new SavedAssistantMessage(null, null);
			}
		}
	}

	/**
	 * 将 Tool 调用流水关联到已保存的助手消息。
	 *
	 * @param traceId   问答链路 ID
	 * @param messageId 助手消息 ID
	 */
	private void attachToolCallLogs(String traceId, String messageId) {
		try {
			this.toolCallLogService.attachMessageId(traceId, messageId);
		}
		catch (Exception ex) {
			log.warn("回填 Tool 调用流水消息 ID 失败: {}", ex.getMessage());
		}
	}

	/**
	 * 原子完成流式消息保存、图表发布、计费和上下文清理。
	 *
	 * @param finalized      单次收口标记
	 * @param signal         终止信号
	 * @param error          上游异常
	 * @param question       用户原始问题
	 * @param conversationId 会话 ID
	 * @param mode           问答模式
	 * @param modelId        模型配置 ID
	 * @param modelName      模型名称
	 * @param traceId        问答链路 ID
	 * @param entCode        租户编码
	 * @param userId         用户 ID
	 * @param startTime      开始时间
	 * @param streamSession  流式最终答案净化会话
	 * @param usageRef       Token 用量
	 * @return 需要继续发送的图表、完成或错误事件
	 */
	private List<ChatStreamFrame> finalizeStream(
			AtomicBoolean finalized, SignalType signal, Throwable error,
			String question, String conversationId, String mode, String modelId,
			String modelName, String traceId,
			String entCode, String userId, long startTime,
			AssistantAnswerSanitizer.StreamSession streamSession,
			AtomicReference<Usage> usageRef) {
		if (!finalized.compareAndSet(false, true)) {
			return List.of();
		}
		TenantContext.setEntCode(entCode);
		TenantContext.setUserId(userId);
		try {
			AssistantAnswerSanitizer.StreamCompletion streamCompletion = streamSession.finish();
			boolean cancelled = signal == SignalType.CANCEL;
			boolean failed = signal == SignalType.ON_ERROR;
			ChatResponse selectionResponse = failed || cancelled ? null
				: this.chartSelectionService.ensureChart(question, streamCompletion.content(),
					modelId, modelName, traceId, conversationId, mode);
			int[] tokens = this.mergeTokenUsage(usageRef.get(), selectionResponse);
			int durationMs = (int) (System.currentTimeMillis() - startTime);
			String toolCalls = this.toolCallRecorder.getToolCallsJson(traceId);
			int toolCallsCount = this.toolCallRecorder.getToolCallCount(traceId);
			String status = cancelled ? "cancelled" : failed ? "error" : "success";
			String errorMessage = failed && error != null ? error.getMessage() : null;
			ChartVO.ChartSpec chart = failed || cancelled ? null
				: this.resolveChart(traceId, entCode, conversationId);
			SavedAssistantMessage savedAssistant = this.saveAssistantWithChartFallback(
				conversationId, streamCompletion.content(), mode, modelName, tokens,
				toolCalls, toolCallsCount, durationMs, status, errorMessage, chart, traceId);
			if (!failed) {
				try {
					this.billingService.deductForTokenUsage(
						tokens[2], tokens[0], tokens[1], modelName, conversationId);
				}
				catch (Exception ex) {
					log.warn("流式计费扣除失败: {}", ex.getMessage());
				}
			}
			if (failed) {
				return List.of(new ChatStreamFrame(
					"error", new ChatVO.StreamError("STREAM_ERROR", "回答生成失败，请稍后重试")));
			}
			if (cancelled) {
				return List.of();
			}
			List<ChatStreamFrame> frames = new ArrayList<>();
			if (!streamCompletion.pendingDelta().isEmpty()) {
				frames.add(new ChatStreamFrame(
					"delta", new ChatVO.StreamDelta(streamCompletion.pendingDelta())));
			}
			if (savedAssistant.chart() != null) {
				frames.add(new ChatStreamFrame(
					"chart", new ChatVO.StreamChart(savedAssistant.chart())));
			}
			frames.add(new ChatStreamFrame(
				"done", new ChatVO.StreamDone(conversationId, "success")));
			return List.copyOf(frames);
		}
		finally {
			this.clearTrace(traceId);
			TenantContext.clear();
		}
	}

	/**
	 * 读取本轮图表，并记录业务结果已捕获但最终仍未生成图表的诊断日志。
	 *
	 * @param traceId        问答链路 ID
	 * @param entCode        租户编码
	 * @param conversationId 会话 ID
	 * @return 本轮有效图表，没有时返回 null
	 */
	private ChartVO.ChartSpec resolveChart(String traceId, String entCode, String conversationId) {
		ChartVO.ChartSpec chart = this.toolResultRecorder.getChart(traceId, entCode, conversationId);
		if (chart == null) {
			int resultCount = this.toolResultRecorder.getResults(traceId, entCode, conversationId).size();
			if (resultCount > 0) {
				log.warn("图表未生成，模型选择的图表类型与本轮结构化数据不兼容: traceId={}, conversationId={}, businessResultCount={}",
					traceId, conversationId, resultCount);
			}
		}
		return chart;
	}

	/**
	 * 合并主回答和图表选择请求的 Token 用量。
	 *
	 * @param response          主回答响应
	 * @param selectionResponse 图表选择响应
	 * @return 合并后的 prompt、completion、total Token
	 */
	private int[] mergeTokenUsage(ChatResponse response, ChatResponse selectionResponse) {
		return this.mergeTokenUsage(response == null ? null : response.getMetadata().getUsage(), selectionResponse);
	}

	/**
	 * 合并流式主回答和图表选择请求的 Token 用量。
	 *
	 * @param usage             主回答 Token 用量
	 * @param selectionResponse 图表选择响应
	 * @return 合并后的 prompt、completion、total Token
	 */
	private int[] mergeTokenUsage(Usage usage, ChatResponse selectionResponse) {
		int[] primaryTokens = this.extractTokenUsage(usage);
		int[] selectionTokens = selectionResponse == null
			? new int[]{0, 0, 0} : this.extractTokenUsage(selectionResponse);
		return new int[]{
			primaryTokens[0] + selectionTokens[0],
			primaryTokens[1] + selectionTokens[1],
			primaryTokens[2] + selectionTokens[2]
		};
	}

}
