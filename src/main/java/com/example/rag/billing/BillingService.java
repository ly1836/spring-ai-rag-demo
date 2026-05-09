package com.example.rag.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.example.rag.dao.entity.BillingTransactionEntity;
import com.example.rag.dao.mapper.BillingAccountMapper;
import com.example.rag.dao.mapper.BillingPlanMapper;
import com.example.rag.dao.mapper.BillingPriceRuleMapper;
import com.example.rag.dao.mapper.BillingTransactionMapper;
import com.example.rag.dao.mapper.TokenUsageDailyMapper;
import com.example.rag.dao.mapper.TokenUsageMonthlyMapper;
import com.example.rag.config.TenantContext;
import com.example.rag.vo.BillingVO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 计费服务 —— 管理租户计费账户、配额校验、token 用量扣费和充值。
 * <p>
 * 核心职责：
 * <ul>
 *   <li>每次 LLM 调用前校验配额（余额、月度额度、账户状态）</li>
 *   <li>每次 LLM 调用后按 token 用量计算费用并扣除</li>
 *   <li>实时更新每日用量聚合表（a_token_usage_daily）</li>
 * </ul>
 * <p>
 * 计费公式：cost = (promptTokens × 输入单价 + completionTokens × 输出单价) / 1000
 * <br>
 * 单价从 a_billing_price_rule 表按模型名称和生效日期查询。
 */
@Service
public class BillingService {

	private static final Logger log = LoggerFactory.getLogger(BillingService.class);

	/** 计费账户 Mapper。 */
	private final BillingAccountMapper accountMapper;

	/** 计费套餐 Mapper。 */
	private final BillingPlanMapper planMapper;

	/** 模型计价规则 Mapper。 */
	private final BillingPriceRuleMapper priceRuleMapper;

	/** 交易流水 Mapper。 */
	private final BillingTransactionMapper transactionMapper;

	/** 每日 token 用量 Mapper。 */
	private final TokenUsageDailyMapper dailyMapper;

	/** 月度 token 用量 Mapper。 */
	private final TokenUsageMonthlyMapper monthlyMapper;

	public BillingService(BillingAccountMapper accountMapper, BillingPlanMapper planMapper,
			BillingPriceRuleMapper priceRuleMapper, BillingTransactionMapper transactionMapper,
			TokenUsageDailyMapper dailyMapper, TokenUsageMonthlyMapper monthlyMapper) {
		this.accountMapper = accountMapper;
		this.planMapper = planMapper;
		this.priceRuleMapper = priceRuleMapper;
		this.transactionMapper = transactionMapper;
		this.dailyMapper = dailyMapper;
		this.monthlyMapper = monthlyMapper;
	}

	/**
	 * 获取当前租户的计费账户详情（关联套餐信息）。
	 *
	 * @return 账户详情 VO
	 * @throws IllegalStateException 未找到计费账户时抛出
	 */
	public BillingVO.AccountResponse getAccount() {
		TenantContext.requireEntCode();
		Map<String, Object> account = accountMapper.selectCurrentAccountWithPlan();
		if (account == null || account.isEmpty()) {
			throw new IllegalStateException("未找到计费账户，请联系管理员");
		}
		return toAccountResponse(account);
	}

	/**
	 * 校验当前租户的配额是否充足，不满足则抛出异常阻止请求。
	 * <p>
	 * 检查顺序：
	 * 1. 账户状态（suspended / arrears → 拒绝）
	 * 2. 免费套餐的月度 token 配额（超限 → 拒绝）
	 * 3. 付费套餐的余额（负数 → 拒绝）
	 * <p>
	 * 无计费账户的租户跳过检查（向前兼容）。
	 *
	 * @throws IllegalStateException 配额不足时抛出，由 GlobalExceptionHandler 捕获
	 */
	public void checkQuota() {
		String entCode = TenantContext.requireEntCode();
		Map<String, Object> account = accountMapper.selectCurrentQuotaInfo();
		if (account == null || account.isEmpty()) {
			log.warn("租户 {} 无计费账户，跳过配额检查", entCode);
			return;
		}

		String status = (String) account.get("status");
		if ("suspended".equals(status) || "arrears".equals(status)) {
			throw new IllegalStateException("账户已被暂停，请充值后重试");
		}

		String planType = (String) account.get("plan_type");
		long monthlyQuota = safeLong(account.get("monthly_token_quota"));
		long usedTokens = safeLong(account.get("used_tokens_this_month"));

		if (monthlyQuota > 0 && usedTokens >= monthlyQuota && "free".equals(planType)) {
			throw new IllegalStateException("本月免费额度已用完，请升级套餐");
		}

		BigDecimal balance = safeBigDecimal(account.get("balance"));
		if (balance.compareTo(BigDecimal.ZERO) < 0 && !"free".equals(planType)) {
			throw new IllegalStateException("账户余额不足，请充值");
		}
	}

	/**
	 * 根据 LLM 调用的 token 用量执行扣费。
	 * <p>
	 * 操作步骤（在事务中执行）：
	 * 1. 按模型计价规则计算费用
	 * 2. 扣减账户余额，累加消费和月度用量
	 * 3. 插入扣费交易流水
	 * 4. 更新每日用量聚合表（ON DUPLICATE KEY UPDATE 实现 upsert）
	 * <p>
	 * 扣费失败仅记录日志，不影响用户获取 LLM 回答。
	 *
	 * @param totalTokens     总 token 数
	 * @param promptTokens    输入 token 数
	 * @param completionTokens 输出 token 数
	 * @param model           模型名称（用于查找计价规则）
	 * @param conversationId  关联的会话 ID（记录到流水中）
	 */
	@Transactional(transactionManager = "erpTransactionManager")
	public void deductForTokenUsage(int totalTokens, int promptTokens, int completionTokens,
			String model, String conversationId) {
		String entCode = TenantContext.requireEntCode();
		BigDecimal cost = calculateCost(promptTokens, completionTokens, model);
		if (cost.compareTo(BigDecimal.ZERO) <= 0 && totalTokens <= 0) {
			return;
		}
		try {
			accountMapper.deduct(cost, totalTokens);

			BigDecimal balanceAfter = queryBalance();

			BillingTransactionEntity transaction = new BillingTransactionEntity();
			transaction.setTransactionNo(UUID.randomUUID().toString());
			transaction.setEntCode(entCode);
			transaction.setType("deduction");
			transaction.setAmount(cost.negate());
			transaction.setBalanceAfter(balanceAfter);
			transaction.setTokenCount(totalTokens);
			transaction.setModel(model);
			transaction.setConversationId(conversationId);
			transaction.setDescription("对话扣费");
			transaction.setOperator("system");
			transactionMapper.insert(transaction);

			updateDailyUsage(model, promptTokens, completionTokens, totalTokens, cost);
		}
		catch (Exception e) {
			log.warn("扣费记录失败: entCode={}, tokens={}, error={}", entCode, totalTokens, e.getMessage());
			throw new IllegalStateException("扣费记录失败，请稍后重试", e);
		}
	}

	/**
	 * 账户充值。
	 * <p>
	 * 增加余额、累加充值总额、激活账户状态，并插入充值交易流水。
	 *
	 * @param amount   充值金额（元）
	 * @param operator 操作人
	 * @return 充值结果（含交易流水号和充值后余额）
	 */
	@Transactional(transactionManager = "erpTransactionManager")
	public BillingVO.RechargeResponse recharge(BigDecimal amount, String operator) {
		String entCode = TenantContext.requireEntCode();
		accountMapper.recharge(amount);

		BigDecimal balanceAfter = queryBalance();

		String txNo = UUID.randomUUID().toString();
		BillingTransactionEntity transaction = new BillingTransactionEntity();
		transaction.setTransactionNo(txNo);
		transaction.setEntCode(entCode);
		transaction.setType("recharge");
		transaction.setAmount(amount);
		transaction.setBalanceAfter(balanceAfter);
		transaction.setDescription("账户充值");
		transaction.setOperator(operator);
		transactionMapper.insert(transaction);

		return new BillingVO.RechargeResponse(txNo, amount, balanceAfter);
	}

	/**
	 * 查询当前租户的交易流水（分页，按时间倒序）。
	 *
	 * @param page 页码（从 0 开始）
	 * @param size 每页数量
	 * @return 交易流水列表
	 */
	public List<BillingVO.TransactionItemResponse> getTransactions(int page, int size) {
		TenantContext.requireEntCode();
		return transactionMapper.selectTransactions(size, page * size);
	}

	/**
	 * 查询所有生效中的计费套餐（按月费升序排列）。
	 *
	 * @return 套餐列表
	 */
	public List<BillingVO.PlanItemResponse> getPlans() {
		return planMapper.selectActivePlans();
	}

	/**
	 * 查询当前租户在指定日期范围内的每日 token 用量。
	 *
	 * @param startDate 开始日期（yyyy-MM-dd）
	 * @param endDate   结束日期（yyyy-MM-dd）
	 * @return 每日用量列表
	 */
	public List<BillingVO.DailyUsageItemResponse> getDailyUsage(String startDate, String endDate) {
		TenantContext.requireEntCode();
		return dailyMapper.selectDailyUsage(startDate, endDate);
	}

	/**
	 * 查询当前租户的月度 token 用量（最近 12 个月）。
	 *
	 * @return 月度用量列表
	 */
	public List<BillingVO.MonthlyUsageItemResponse> getMonthlyUsage() {
		TenantContext.requireEntCode();
		return monthlyMapper.selectMonthlyUsage();
	}

	/** 查询当前余额，null 安全（查不到或值为 null 时返回 ZERO） */
	private BigDecimal queryBalance() {
		BigDecimal balance = accountMapper.selectCurrentBalance();
		return balance != null ? balance : BigDecimal.ZERO;
	}

	/** Number → long 安全转换，避免 null 拆箱 NPE */
	private static long safeLong(Object value) {
		return value instanceof Number n ? n.longValue() : 0L;
	}

	/** Object → BigDecimal 安全转换，避免 null 或类型不匹配 */
	private static BigDecimal safeBigDecimal(Object value) {
		return value instanceof BigDecimal bd ? bd : BigDecimal.ZERO;
	}

	/**
	 * 根据模型计价规则计算本次调用费用。
	 * 查找当前生效的计价规则，按输入/输出 token 分别计价后求和。
	 * 未找到计价规则时按零计费（不阻止请求）。
	 */
	private BigDecimal calculateCost(int promptTokens, int completionTokens, String model) {
		Map<String, Object> rule = priceRuleMapper.selectActiveRule(model);
		if (rule == null || rule.isEmpty()) {
			log.warn("未找到模型 {} 的计价规则，按零计费", model);
			return BigDecimal.ZERO;
		}
		BigDecimal inputPrice = safeBigDecimal(rule.get("input_price_per_1k"));
		BigDecimal outputPrice = safeBigDecimal(rule.get("output_price_per_1k"));
		return inputPrice.multiply(BigDecimal.valueOf(promptTokens))
			.divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP)
			.add(outputPrice.multiply(BigDecimal.valueOf(completionTokens))
				.divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP));
	}

	/**
	 * 更新每日 token 用量聚合表。
	 * 使用 MySQL 的 ON DUPLICATE KEY UPDATE 实现 upsert：
	 * 首次插入创建记录，后续调用累加 request_count / tokens / cost。
	 */
	private void updateDailyUsage(String model,
			int promptTokens, int completionTokens, int totalTokens, BigDecimal cost) {
		String entCode = TenantContext.requireEntCode();
		String userId = TenantContext.getUserIdOrDefault();
		dailyMapper.upsertDailyUsage(entCode, userId, model, promptTokens, completionTokens, totalTokens, cost);
	}

	private BillingVO.AccountResponse toAccountResponse(Map<String, Object> account) {
		return new BillingVO.AccountResponse(
			(String) account.get("ent_code"),
			(String) account.get("plan_code"),
			(String) account.get("plan_name"),
			(String) account.get("plan_type"),
			safeBigDecimal(account.get("balance")),
			safeBigDecimal(account.get("total_recharged")),
			safeBigDecimal(account.get("total_consumed")),
			safeLong(account.get("used_tokens_this_month")),
			safeLong(account.get("monthly_token_quota")),
			safeBigDecimal(account.get("monthly_price")),
			safeInteger(account.get("max_conversations_per_day")),
			safeInteger(account.get("max_tokens_per_request")),
			safeInteger(account.get("max_users")),
			String.valueOf(account.get("billing_cycle_start")),
			(String) account.get("status"));
	}

	private static Integer safeInteger(Object value) {
		return value instanceof Number n ? n.intValue() : null;
	}

}
