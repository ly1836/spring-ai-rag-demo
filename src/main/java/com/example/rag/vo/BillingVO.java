package com.example.rag.vo;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * 计费模块 VO —— {@link com.example.rag.controller.BillingController} 的入参和出参定义。
 * <p>
 * 包含账户查询、套餐列表、交易流水、充值、用量统计的请求/响应对象。
 */
public final class BillingVO {

	private BillingVO() {
	}

	// ==================== Request ====================

	/**
	 * 充值入参（通过 @RequestBody JSON 提交）。
	 *
	 * @param amount   充值金额（元），必须大于 0
	 * @param operator 操作人姓名，默认 "system"
	 */
	public record RechargeRequest(BigDecimal amount, String operator) {
		public RechargeRequest {
			operator = (operator == null || operator.isBlank()) ? "system" : operator;
		}
	}

	/**
	 * 交易流水查询入参。
	 *
	 * @param page 页码（从 0 开始），默认 0
	 * @param size 每页数量，默认 20
	 */
	public record TransactionQueryRequest(Integer page, Integer size) {
		public TransactionQueryRequest {
			page = (page == null || page < 0) ? 0 : page;
			size = (size == null || size <= 0) ? 20 : size;
		}
	}

	/**
	 * 每日 token 用量查询入参。
	 *
	 * @param startDate 开始日期，格式 yyyy-MM-dd
	 * @param endDate   结束日期，格式 yyyy-MM-dd
	 */
	public record DailyUsageQueryRequest(String startDate, String endDate) {
		public DailyUsageQueryRequest {
			startDate = Objects.requireNonNullElse(startDate, "");
			endDate = Objects.requireNonNullElse(endDate, "");
		}
	}

	// ==================== Response ====================

	/**
	 * 租户计费账户详情（关联套餐信息）。
	 *
	 * @param entCode               租户编码
	 * @param planCode              当前套餐编码
	 * @param planName              套餐名称
	 * @param planType              套餐类型：free / basic / pro / enterprise
	 * @param balance               账户余额（元）
	 * @param totalRecharged        累计充值金额
	 * @param totalConsumed         累计消费金额
	 * @param usedTokensThisMonth   本月已用 token 数
	 * @param monthlyTokenQuota     每月 token 配额（0 = 不限）
	 * @param monthlyPrice          套餐月费
	 * @param maxConversationsPerDay 每日最大会话数（null = 不限）
	 * @param maxTokensPerRequest   单次请求最大 token 数（null = 不限）
	 * @param maxUsers              最大用户数（null = 不限）
	 * @param billingCycleStart     当前计费周期起始日
	 * @param status                账户状态：active / suspended / arrears
	 */
	public record AccountResponse(String entCode, String planCode, String planName, String planType,
			BigDecimal balance, BigDecimal totalRecharged, BigDecimal totalConsumed,
			Long usedTokensThisMonth, Long monthlyTokenQuota, BigDecimal monthlyPrice,
			Integer maxConversationsPerDay, Integer maxTokensPerRequest, Integer maxUsers,
			String billingCycleStart, String status) {
	}

	/**
	 * 单个计费套餐详情。
	 *
	 * @param planCode              套餐编码
	 * @param planName              套餐名称
	 * @param planType              套餐类型
	 * @param monthlyTokenQuota     每月 token 配额
	 * @param monthlyPrice          月费（元）
	 * @param overagePricePer1k     超额部分每千 token 单价（元）
	 * @param maxConversationsPerDay 每日最大会话数
	 * @param maxTokensPerRequest   单次请求最大 token 数
	 * @param maxUsers              最大用户数
	 * @param features              套餐功能描述（JSON 字符串）
	 */
	public record PlanItemResponse(
			String planCode, String planName, String planType,
			long monthlyTokenQuota, BigDecimal monthlyPrice, BigDecimal overagePricePer1k,
			Integer maxConversationsPerDay, Integer maxTokensPerRequest, Integer maxUsers,
			String features) {
	}

	/**
	 * 单条交易流水记录。
	 *
	 * @param transactionNo  交易流水号（UUID）
	 * @param type           交易类型：recharge / deduction / refund / monthly_fee / gift
	 * @param amount         交易金额（正 = 入账，负 = 扣除）
	 * @param balanceAfter   交易后账户余额
	 * @param tokenCount     关联的 token 数量（扣费时记录）
	 * @param model          关联的模型名称（扣费时记录）
	 * @param conversationId 关联的会话 ID（扣费时记录）
	 * @param description    交易描述
	 * @param operator       操作人
	 * @param createdAt      交易时间
	 */
	public record TransactionItemResponse(
			String transactionNo, String type, BigDecimal amount, BigDecimal balanceAfter,
			Integer tokenCount, String model, String conversationId,
			String description, String operator, String createdAt) {
	}

	/**
	 * 单日 token 用量统计（按租户 + 用户 + 模型维度聚合）。
	 *
	 * @param userId                用户 ID
	 * @param usageDate             统计日期
	 * @param model                 模型名称
	 * @param requestCount          请求次数
	 * @param totalPromptTokens     累计输入 token 数
	 * @param totalCompletionTokens 累计输出 token 数
	 * @param totalTokens           累计总 token 数
	 * @param totalToolCalls        累计工具调用次数
	 * @param estimatedCost         估算费用（元）
	 */
	public record DailyUsageItemResponse(
			String userId, String usageDate, String model, int requestCount,
			int totalPromptTokens, int totalCompletionTokens, int totalTokens,
			int totalToolCalls, BigDecimal estimatedCost) {
	}

	/**
	 * 单月 token 用量统计（按租户 + 模型维度聚合）。
	 *
	 * @param usageMonth            统计月份（yyyy-MM）
	 * @param model                 模型名称
	 * @param requestCount          请求次数
	 * @param totalPromptTokens     累计输入 token 数
	 * @param totalCompletionTokens 累计输出 token 数
	 * @param totalTokens           累计总 token 数
	 * @param activeUsers           活跃用户数
	 * @param estimatedCost         估算费用（元）
	 */
	public record MonthlyUsageItemResponse(
			String usageMonth, String model, int requestCount,
			long totalPromptTokens, long totalCompletionTokens, long totalTokens,
			int activeUsers, BigDecimal estimatedCost) {
	}

	/** 套餐列表出参 */
	public record PlansResponse(List<PlanItemResponse> plans) {
	}

	/**
	 * 交易流水列表出参（分页）。
	 *
	 * @param page 当前页码
	 * @param size 每页数量
	 * @param data 交易流水列表
	 */
	public record TransactionsResponse(int page, int size, List<TransactionItemResponse> data) {
	}

	/**
	 * 充值出参。
	 *
	 * @param transactionNo 充值交易流水号
	 * @param amount        充值金额
	 * @param balanceAfter  充值后账户余额
	 */
	public record RechargeResponse(String transactionNo, BigDecimal amount, BigDecimal balanceAfter) {
	}

	/**
	 * 每日用量列表出参。
	 *
	 * @param startDate 查询起始日期
	 * @param endDate   查询结束日期
	 * @param data      每日用量列表
	 */
	public record DailyUsageResponse(String startDate, String endDate, List<DailyUsageItemResponse> data) {
	}

	/** 月度用量列表出参 */
	public record MonthlyUsageResponse(List<MonthlyUsageItemResponse> data) {
	}

}
