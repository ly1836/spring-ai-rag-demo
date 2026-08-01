package com.example.rag.chat.guard;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.example.rag.chat.chart.capture.ToolResultRecorder;
import com.example.rag.tool.trace.ToolCallRecorder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;

/**
 * 当前轮业务数据守卫，禁止模型直接复用历史回答中的业务数字。
 */
@Component
public class BusinessDataTurnGuard {

	private static final Logger log = LoggerFactory.getLogger(BusinessDataTurnGuard.class);

	/** ERP 业务对象关键词。 */
	private static final Pattern BUSINESS_TERM = Pattern.compile(
		"销售|订单|库存|仓库|仓储|工单|售后|采购|供应商|生产|质检|检验|客户|应收|应付|财务|发票|委外|物料|产品|货品|批次|账龄|金额|数量|合格率|余额"
			+ "|\\b(?:sales?|orders?|inventory|stock|warehouses?|work\\s*orders?|after[-\\s]?sales|"
			+ "purchases?|procurement|suppliers?|production|quality\\s*inspections?|customers?|"
			+ "accounts?\\s*receivable|accounts?\\s*payable|finance|invoices?|outsourcing|materials?|"
			+ "products?|goods|batches?|aging|amounts?|quantities?|balances?)\\b",
		Pattern.CASE_INSENSITIVE);

	/** 查询、统计或展示意图关键词。 */
	private static final Pattern DATA_INTENT = Pattern.compile(
		"查询|查找|查到|统计|分析|比较|对比|分布|趋势|占比|汇总|明细|列表|排名|排行|TOP|最多|最少|多少|哪些|对应|在哪|情况|进度|状态|怎么样|怎样|展示|图"
			+ "|\\b(?:query|search|find|show|display|list|count|statistics?|analy(?:ze|sis)|"
			+ "compar(?:e|ison)|distribution|trends?|percentages?|ratios?|summar(?:y|ize)|details?|"
			+ "rankings?|top|most|least|which|where|status|progress|latest|current|recent|totals?|"
			+ "averages?)\\b|how\\s+(?:many|much)",
		Pattern.CASE_INSENSITIVE);

	/** 英文直接请求业务记录的问句和祈使句。 */
	private static final Pattern ENGLISH_DIRECT_DATA_REQUEST = Pattern.compile(
		"\\b(?:what\\s+(?:are|were)\\s+(?:the\\s+)?|(?:give|tell)\\s+me\\s+(?:(?:the|all|some)\\s+)?)"
			+ "(?:sales\\s+orders?|orders?|inventory|stock|warehouses?|work\\s*orders?|after[-\\s]?sales\\s+tickets?|"
			+ "purchases?|procurement\\s+orders?|production\\s+orders?|quality\\s+inspections?|"
			+ "accounts?\\s*receivable|accounts?\\s*payable|invoices?|batches?|aging\\s+reports?|balances?)\\b",
		Pattern.CASE_INSENSITIVE);

	/** 首次调用附加的当前轮数据约束。 */
	private static final String CURRENT_TURN_PROMPT = """
			【当前轮数据约束】
			历史消息只可用于理解业务主体和指代，历史回答中的数字、表格和结论不是本轮数据源。
			当前问题涉及 ERP 业务数据时，必须在本轮重新调用相关业务查询工具取得实时结构化结果，不得直接复用历史回答。
			业务结果适合可视化时，应在业务查询完成后选择图表类型和标题。

			【当前问题】
			%s
			""";

	/** 有界重试附加的当前轮数据约束。 */
	private static final String RETRY_PROMPT = """
			【当前轮业务查询重试】
			上一次回答没有取得本轮业务查询结果，不得继续使用历史回答中的数字或表格。
			必须先调用与当前问题匹配的业务查询工具；如缺少必要查询条件，请只使用业务语言说明需要补充的条件。
			业务结果适合可视化时，应在业务查询完成后选择图表类型和标题。

			【当前问题】
			%s
			""";

	/** 业务 Tool 结果记录器。 */
	private final ToolResultRecorder toolResultRecorder;

	/** 业务 Tool 调用记录器。 */
	private final ToolCallRecorder toolCallRecorder;

	/**
	 * 创建当前轮业务数据守卫。
	 *
	 * @param toolResultRecorder 业务 Tool 结果记录器
	 * @param toolCallRecorder   业务 Tool 调用记录器
	 */
	public BusinessDataTurnGuard(ToolResultRecorder toolResultRecorder,
			ToolCallRecorder toolCallRecorder) {
		this.toolResultRecorder = toolResultRecorder;
		this.toolCallRecorder = toolCallRecorder;
	}

	/**
	 * 为首次模型调用补充当前轮数据约束。
	 *
	 * @param question 用户原始问题
	 * @return 仅供模型使用的增强问题
	 */
	public String currentTurnQuestion(String question) {
		return CURRENT_TURN_PROMPT.formatted(question);
	}

	/**
	 * 校验非流式业务问答是否取得当前轮数据，缺失时最多重试一次。
	 *
	 * @param initialResponse 首次模型响应
	 * @param retrySupplier   重试模型调用
	 * @param mode            问答模式
	 * @param question        用户原始问题
	 * @param traceId         问答链路 ID
	 * @param entCode         租户编码
	 * @param conversationId  会话 ID
	 * @return 最终采用的模型响应，重试时合并首次调用 Token
	 */
	public ChatResponse ensureNonStreaming(ChatResponse initialResponse,
			Supplier<ChatResponse> retrySupplier, String mode, String question,
			String traceId, String entCode, String conversationId) {
		if (!this.requiresCurrentBusinessData(mode, question)
				|| this.hasBusinessResult(traceId, entCode, conversationId)) {
			return initialResponse;
		}
		this.logRetryReason(traceId, conversationId);
		ChatResponse retryResponse = retrySupplier.get();
		if (!this.hasBusinessResult(traceId, entCode, conversationId)) {
			this.logRetryFailure(traceId, conversationId);
		}
		return this.mergeUsage(retryResponse, this.lastUsage(List.of(initialResponse)));
	}

	/**
	 * 校验流式业务问答是否取得当前轮数据，未确认数据来源前不发送首次回答。
	 *
	 * @param initialFlux     首次模型响应流
	 * @param retrySupplier   重试模型响应流
	 * @param mode            问答模式
	 * @param question        用户原始问题
	 * @param traceId         问答链路 ID
	 * @param entCode         租户编码
	 * @param conversationId  会话 ID
	 * @return 已确认当前轮数据来源的模型响应流
	 */
	public Flux<ChatResponse> ensureStreaming(Flux<ChatResponse> initialFlux,
			Supplier<Flux<ChatResponse>> retrySupplier, String mode, String question,
			String traceId, String entCode, String conversationId) {
		if (!this.requiresCurrentBusinessData(mode, question)) {
			return initialFlux;
		}
		return Flux.defer(() -> {
			// 首次调用只记录最后一次有效 usage，不再为 Token 合并缓存完整响应流。
			AtomicReference<int[]> initialUsageRef = new AtomicReference<>(new int[]{0, 0, 0});
			AtomicBoolean initialResponseEmitted = new AtomicBoolean();
			Flux<ChatResponse> guardedInitialFlux = this.gateUntilBusinessResult(
				initialFlux.doOnNext(response -> {
					Usage usage = response.getMetadata().getUsage();
					if (usage != null && this.safeToken(usage.getTotalTokens()) > 0) {
						initialUsageRef.set(new int[]{this.safeToken(usage.getPromptTokens()),
							this.safeToken(usage.getCompletionTokens()), this.safeToken(usage.getTotalTokens())});
					}
				}), traceId, entCode, conversationId, false)
				.doOnNext(response -> initialResponseEmitted.set(true));
			return guardedInitialFlux.concatWith(Flux.defer(() -> {
				if (this.hasBusinessResult(traceId, entCode, conversationId)
						&& initialResponseEmitted.get()) {
					return Flux.empty();
				}
				this.logRetryReason(traceId, conversationId);
				// 重试仍先确认当前轮数据；确实无数据时仅在完成阶段释放安全兜底回答。
				Flux<ChatResponse> guardedRetryFlux = this.gateUntilBusinessResult(
					retrySupplier.get(), traceId, entCode, conversationId, true)
					.doOnComplete(() -> {
						if (!this.hasBusinessResult(traceId, entCode, conversationId)) {
							this.logRetryFailure(traceId, conversationId);
						}
					});
				return this.mergeStreamingUsage(guardedRetryFlux, initialUsageRef.get())
					.switchIfEmpty(Flux.error(new IllegalStateException("业务数据查询重试未返回模型响应")));
			}));
		});
	}

	/**
	 * 构造仅允许本轮业务查询的重试问题。
	 *
	 * @param question 用户原始问题
	 * @return 仅供模型使用的重试问题
	 */
	public String retryQuestion(String question) {
		return RETRY_PROMPT.formatted(question);
	}

	/**
	 * 判断当前问题是否要求取得 ERP 业务数据。
	 *
	 * @param mode     问答模式
	 * @param question 用户问题
	 * @return 是否要求当前轮业务数据
	 */
	public boolean requiresCurrentBusinessData(String mode, String question) {
		if ("data".equals(mode)) {
			return true;
		}
		return "auto".equals(mode) && question != null
			&& BUSINESS_TERM.matcher(question).find()
			&& (DATA_INTENT.matcher(question).find()
				|| ENGLISH_DIRECT_DATA_REQUEST.matcher(question).find());
	}

	/**
	 * 判断当前链路是否已经捕获非空业务结果。
	 *
	 * @param traceId        问答链路 ID
	 * @param entCode        租户编码
	 * @param conversationId 会话 ID
	 * @return 是否存在业务结果
	 */
	private boolean hasBusinessResult(String traceId, String entCode, String conversationId) {
		return !this.toolResultRecorder.getResults(traceId, entCode, conversationId).isEmpty();
	}

	/**
	 * 记录首次调用未取得当前轮业务结果的具体原因。
	 *
	 * @param traceId        问答链路 ID
	 * @param conversationId 会话 ID
	 */
	private void logRetryReason(String traceId, String conversationId) {
		int toolCallCount = this.toolCallRecorder.getToolCallCount(traceId);
		if (toolCallCount == 0) {
			log.warn("本轮未调用业务 Tool，执行一次有界查询重试: traceId={}, conversationId={}",
				traceId, conversationId);
			return;
		}
		log.warn("本轮业务 Tool 已调用但未取得非空结构化结果，执行一次有界查询重试: traceId={}, conversationId={}, toolCallCount={}",
			traceId, conversationId, toolCallCount);
	}

	/**
	 * 记录有界重试后仍未取得当前轮业务结果。
	 *
	 * @param traceId        问答链路 ID
	 * @param conversationId 会话 ID
	 */
	private void logRetryFailure(String traceId, String conversationId) {
		log.warn("本轮业务查询重试后仍无可用结构化结果: traceId={}, conversationId={}, toolCallCount={}",
			traceId, conversationId, this.toolCallRecorder.getToolCallCount(traceId));
	}

	/**
	 * 将额外 Token 用量合并到最终非流式响应。
	 *
	 * @param response        最终模型响应
	 * @param additionalUsage 额外 Token 用量
	 * @return 合并 Token 后的模型响应
	 */
	private ChatResponse mergeUsage(ChatResponse response, int[] additionalUsage) {
		if (response == null) {
			throw new IllegalStateException("业务数据查询重试未返回模型响应");
		}
		Usage usage = response.getMetadata().getUsage();
		int promptTokens = additionalUsage[0] + this.safeToken(usage == null ? null : usage.getPromptTokens());
		int completionTokens = additionalUsage[1] + this.safeToken(usage == null ? null : usage.getCompletionTokens());
		int totalTokens = additionalUsage[2] + this.safeToken(usage == null ? null : usage.getTotalTokens());
		ChatResponseMetadata metadata = ChatResponseMetadata.builder()
			.id(response.getMetadata().getId())
			.model(response.getMetadata().getModel())
			.rateLimit(response.getMetadata().getRateLimit())
			.promptMetadata(response.getMetadata().getPromptMetadata())
			.usage(new DefaultUsage(promptTokens, completionTokens, totalTokens))
			.build();
		return ChatResponse.builder().from(response).metadata(metadata).build();
	}

	/**
	 * 读取一组流式响应中最后一次有效 Token 用量。
	 *
	 * @param responses 模型响应列表
	 * @return prompt、completion、total Token
	 */
	private int[] lastUsage(List<ChatResponse> responses) {
		for (int i = responses.size() - 1; i >= 0; i--) {
			Usage usage = responses.get(i).getMetadata().getUsage();
			if (usage != null && this.safeToken(usage.getTotalTokens()) > 0) {
				return new int[]{this.safeToken(usage.getPromptTokens()),
					this.safeToken(usage.getCompletionTokens()), this.safeToken(usage.getTotalTokens())};
			}
		}
		return new int[]{0, 0, 0};
	}

	/**
	 * 安全读取可空 Token 数量。
	 *
	 * @param value 可空 Token 数量
	 * @return 非空 Token 数量
	 */
	private int safeToken(Integer value) {
		return value == null ? 0 : value;
	}

	/**
	 * 在当前轮业务结果确认前暂存响应前缀，确认后立即恢复逐分片输出。
	 *
	 * @param responseFlux            模型响应流
	 * @param traceId                 问答链路 ID
	 * @param entCode                 租户编码
	 * @param conversationId          会话 ID
	 * @param emitBufferedWhenMissing 完成后仍无结果时是否释放兜底回答
	 * @return 通过当前轮业务结果校验的模型响应流
	 */
	private Flux<ChatResponse> gateUntilBusinessResult(Flux<ChatResponse> responseFlux,
			String traceId, String entCode, String conversationId, boolean emitBufferedWhenMissing) {
		return Flux.defer(() -> {
			// 每次订阅独享门控状态，避免不同请求之间共享暂存分片。
			List<ChatResponse> pendingResponses = new ArrayList<>();
			AtomicBoolean verified = new AtomicBoolean();
			Flux<ChatResponse> verifiedFlux = responseFlux.concatMap(response -> {
				if (verified.get()) {
					return Flux.just(response);
				}
				pendingResponses.add(response);
				if (!this.hasBusinessResult(traceId, entCode, conversationId)) {
					return Flux.empty();
				}
				// Tool 结果一经确认只释放当前响应，确认前的查询或规划旁白直接丢弃。
				verified.set(true);
				pendingResponses.clear();
				return Flux.just(response);
			});
			return verifiedFlux.concatWith(Flux.defer(() -> {
				if (verified.get() || pendingResponses.isEmpty()) {
					return Flux.empty();
				}
				if (this.hasBusinessResult(traceId, entCode, conversationId)) {
					// 完成时才确认结果说明没有可证明属于最终答案的分片，交由有界重试处理。
					pendingResponses.clear();
					return Flux.empty();
				}
				if (!emitBufferedWhenMissing) {
					// 首次调用没有当前轮数据时丢弃未验证回答，交由有界重试生成最终内容。
					pendingResponses.clear();
					return Flux.empty();
				}
				List<ChatResponse> releasedResponses = List.copyOf(pendingResponses);
				pendingResponses.clear();
				return Flux.fromIterable(releasedResponses);
			}));
		});
	}

	/**
	 * 将首次调用 Token 持续合并到重试响应元数据，同时保持响应逐分片发送。
	 *
	 * @param responseFlux    重试模型响应流
	 * @param additionalUsage 首次调用 Token 用量
	 * @return 已合并 Token 且不缓存完整响应的模型响应流
	 */
	private Flux<ChatResponse> mergeStreamingUsage(Flux<ChatResponse> responseFlux,
			int[] additionalUsage) {
		return Flux.defer(() -> {
			// Provider 通常只在末尾返回 usage；后续空 usage 分片继续携带最后一次累计值。
			AtomicReference<int[]> latestUsageRef = new AtomicReference<>(new int[]{0, 0, 0});
			return responseFlux.map(response -> {
				Usage usage = response.getMetadata().getUsage();
				boolean hasCurrentUsage = usage != null && this.safeToken(usage.getTotalTokens()) > 0;
				if (hasCurrentUsage) {
					latestUsageRef.set(new int[]{this.safeToken(usage.getPromptTokens()),
						this.safeToken(usage.getCompletionTokens()), this.safeToken(usage.getTotalTokens())});
				}
				int[] latestUsage = latestUsageRef.get();
				int[] mergedAdditionalUsage = hasCurrentUsage
					? additionalUsage
					: new int[]{additionalUsage[0] + latestUsage[0],
						additionalUsage[1] + latestUsage[1], additionalUsage[2] + latestUsage[2]};
				return this.mergeUsage(response, mergedAdditionalUsage);
			});
		});
	}
}
