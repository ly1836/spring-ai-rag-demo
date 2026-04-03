package com.example.rag.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.example.rag.config.TenantContext;
import com.example.rag.vo.BillingVO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
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

	private final JdbcTemplate erp;

	public BillingService(@Qualifier("erpJdbcTemplate") JdbcTemplate erpJdbcTemplate) {
		this.erp = erpJdbcTemplate;
	}

	/**
	 * 获取当前租户的计费账户详情（关联套餐信息）。
	 *
	 * @return 账户详情 VO
	 * @throws IllegalStateException 未找到计费账户时抛出
	 */
	public BillingVO.AccountResponse getAccount() {
		String entCode = TenantContext.requireEntCode();
		try {
			return erp.queryForObject(
				"SELECT a.ent_code, a.plan_code, a.balance, a.total_recharged, a.total_consumed, " +
				"a.used_tokens_this_month, a.billing_cycle_start, a.status, " +
				"p.plan_name, p.plan_type, p.monthly_token_quota, p.monthly_price, " +
				"p.max_conversations_per_day, p.max_tokens_per_request, p.max_users " +
				"FROM a_billing_account a JOIN a_billing_plan p ON a.plan_code = p.plan_code " +
				"WHERE a.ent_code = ?",
				(rs, rowNum) -> new BillingVO.AccountResponse(
					rs.getString("ent_code"),
					rs.getString("plan_code"),
					rs.getString("plan_name"),
					rs.getString("plan_type"),
					rs.getBigDecimal("balance"),
					rs.getBigDecimal("total_recharged"),
					rs.getBigDecimal("total_consumed"),
					rs.getLong("used_tokens_this_month"),
					rs.getLong("monthly_token_quota"),
					rs.getBigDecimal("monthly_price"),
					rs.getObject("max_conversations_per_day", Integer.class),
					rs.getObject("max_tokens_per_request", Integer.class),
					rs.getObject("max_users", Integer.class),
					rs.getString("billing_cycle_start"),
					rs.getString("status")),
				entCode);
		}
		catch (EmptyResultDataAccessException e) {
			throw new IllegalStateException("未找到计费账户，请联系管理员");
		}
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
		Map<String, Object> account;
		try {
			account = erp.queryForMap(
				"SELECT a.balance, a.used_tokens_this_month, a.status, " +
				"p.monthly_token_quota, p.plan_type " +
				"FROM a_billing_account a JOIN a_billing_plan p ON a.plan_code = p.plan_code " +
				"WHERE a.ent_code = ?",
				entCode);
		}
		catch (EmptyResultDataAccessException e) {
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
	@Transactional
	public void deductForTokenUsage(int totalTokens, int promptTokens, int completionTokens,
			String model, String conversationId) {
		String entCode = TenantContext.requireEntCode();
		BigDecimal cost = calculateCost(promptTokens, completionTokens, model);
		if (cost.compareTo(BigDecimal.ZERO) <= 0 && totalTokens <= 0) {
			return;
		}
		try {
			erp.update(
				"UPDATE a_billing_account SET " +
				"balance = balance - ?, total_consumed = total_consumed + ?, " +
				"used_tokens_this_month = used_tokens_this_month + ?, updated_at = NOW() " +
				"WHERE ent_code = ?",
				cost, cost, totalTokens, entCode);

			BigDecimal balanceAfter = queryBalance(entCode);

			erp.update(
				"INSERT INTO a_billing_transaction " +
				"(transaction_no, ent_code, type, amount, balance_after, token_count, model, " +
				"conversation_id, description, operator) " +
				"VALUES (?, ?, 'deduction', ?, ?, ?, ?, ?, ?, 'system')",
				UUID.randomUUID().toString(), entCode,
				cost.negate(), balanceAfter, totalTokens, model,
				conversationId, "对话扣费");

			updateDailyUsage(entCode, model, promptTokens, completionTokens, totalTokens, cost);
		}
		catch (Exception e) {
			log.warn("扣费记录失败: entCode={}, tokens={}, error={}", entCode, totalTokens, e.getMessage());
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
	@Transactional
	public BillingVO.RechargeResponse recharge(BigDecimal amount, String operator) {
		String entCode = TenantContext.requireEntCode();
		erp.update(
			"UPDATE a_billing_account SET balance = balance + ?, total_recharged = total_recharged + ?, " +
			"status = 'active', updated_at = NOW() WHERE ent_code = ?",
			amount, amount, entCode);

		BigDecimal balanceAfter = queryBalance(entCode);

		String txNo = UUID.randomUUID().toString();
		erp.update(
			"INSERT INTO a_billing_transaction " +
			"(transaction_no, ent_code, type, amount, balance_after, description, operator) " +
			"VALUES (?, ?, 'recharge', ?, ?, '账户充值', ?)",
			txNo, entCode, amount, balanceAfter, operator);

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
		String entCode = TenantContext.requireEntCode();
		return erp.query(
			"SELECT transaction_no, type, amount, balance_after, token_count, model, " +
			"conversation_id, description, operator, created_at " +
			"FROM a_billing_transaction WHERE ent_code = ? " +
			"ORDER BY created_at DESC LIMIT ? OFFSET ?",
			(rs, rowNum) -> new BillingVO.TransactionItemResponse(
				rs.getString("transaction_no"),
				rs.getString("type"),
				rs.getBigDecimal("amount"),
				rs.getBigDecimal("balance_after"),
				rs.getObject("token_count", Integer.class),
				rs.getString("model"),
				rs.getString("conversation_id"),
				rs.getString("description"),
				rs.getString("operator"),
				rs.getString("created_at")),
			entCode, size, page * size);
	}

	/**
	 * 查询所有生效中的计费套餐（按月费升序排列）。
	 *
	 * @return 套餐列表
	 */
	public List<BillingVO.PlanItemResponse> getPlans() {
		return erp.query(
			"SELECT plan_code, plan_name, plan_type, monthly_token_quota, monthly_price, " +
			"overage_price_per_1k, max_conversations_per_day, max_tokens_per_request, " +
			"max_users, features " +
			"FROM a_billing_plan WHERE status = 'active' ORDER BY monthly_price",
			(rs, rowNum) -> new BillingVO.PlanItemResponse(
				rs.getString("plan_code"),
				rs.getString("plan_name"),
				rs.getString("plan_type"),
				rs.getLong("monthly_token_quota"),
				rs.getBigDecimal("monthly_price"),
				rs.getBigDecimal("overage_price_per_1k"),
				rs.getObject("max_conversations_per_day", Integer.class),
				rs.getObject("max_tokens_per_request", Integer.class),
				rs.getObject("max_users", Integer.class),
				rs.getString("features")));
	}

	/**
	 * 查询当前租户在指定日期范围内的每日 token 用量。
	 *
	 * @param startDate 开始日期（yyyy-MM-dd）
	 * @param endDate   结束日期（yyyy-MM-dd）
	 * @return 每日用量列表
	 */
	public List<BillingVO.DailyUsageItemResponse> getDailyUsage(String startDate, String endDate) {
		String entCode = TenantContext.requireEntCode();
		return erp.query(
			"SELECT user_id, usage_date, model, request_count, " +
			"total_prompt_tokens, total_completion_tokens, total_tokens, " +
			"total_tool_calls, estimated_cost " +
			"FROM a_token_usage_daily WHERE ent_code = ? AND usage_date BETWEEN ? AND ? " +
			"ORDER BY usage_date DESC",
			(rs, rowNum) -> new BillingVO.DailyUsageItemResponse(
				rs.getString("user_id"),
				rs.getString("usage_date"),
				rs.getString("model"),
				rs.getInt("request_count"),
				rs.getInt("total_prompt_tokens"),
				rs.getInt("total_completion_tokens"),
				rs.getInt("total_tokens"),
				rs.getInt("total_tool_calls"),
				rs.getBigDecimal("estimated_cost")),
			entCode, startDate, endDate);
	}

	/**
	 * 查询当前租户的月度 token 用量（最近 12 个月）。
	 *
	 * @return 月度用量列表
	 */
	public List<BillingVO.MonthlyUsageItemResponse> getMonthlyUsage() {
		String entCode = TenantContext.requireEntCode();
		return erp.query(
			"SELECT usage_month, model, request_count, " +
			"total_prompt_tokens, total_completion_tokens, total_tokens, " +
			"active_users, estimated_cost " +
			"FROM a_token_usage_monthly WHERE ent_code = ? ORDER BY usage_month DESC LIMIT 12",
			(rs, rowNum) -> new BillingVO.MonthlyUsageItemResponse(
				rs.getString("usage_month"),
				rs.getString("model"),
				rs.getInt("request_count"),
				rs.getLong("total_prompt_tokens"),
				rs.getLong("total_completion_tokens"),
				rs.getLong("total_tokens"),
				rs.getInt("active_users"),
				rs.getBigDecimal("estimated_cost")),
			entCode);
	}

	/** 查询当前余额，null 安全（查不到或值为 null 时返回 ZERO） */
	private BigDecimal queryBalance(String entCode) {
		try {
			BigDecimal balance = erp.queryForObject(
				"SELECT balance FROM a_billing_account WHERE ent_code = ?",
				BigDecimal.class, entCode);
			return balance != null ? balance : BigDecimal.ZERO;
		}
		catch (EmptyResultDataAccessException e) {
			return BigDecimal.ZERO;
		}
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
		try {
			Map<String, Object> rule = erp.queryForMap(
				"SELECT input_price_per_1k, output_price_per_1k FROM a_billing_price_rule " +
				"WHERE model = ? AND effective_date <= CURDATE() " +
				"AND (expired_date IS NULL OR expired_date >= CURDATE()) " +
				"ORDER BY effective_date DESC LIMIT 1",
				model);
			BigDecimal inputPrice = safeBigDecimal(rule.get("input_price_per_1k"));
			BigDecimal outputPrice = safeBigDecimal(rule.get("output_price_per_1k"));
			return inputPrice.multiply(BigDecimal.valueOf(promptTokens))
				.divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP)
				.add(outputPrice.multiply(BigDecimal.valueOf(completionTokens))
					.divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP));
		}
		catch (EmptyResultDataAccessException e) {
			log.warn("未找到模型 {} 的计价规则，按零计费", model);
			return BigDecimal.ZERO;
		}
	}

	/**
	 * 更新每日 token 用量聚合表。
	 * 使用 MySQL 的 ON DUPLICATE KEY UPDATE 实现 upsert：
	 * 首次插入创建记录，后续调用累加 request_count / tokens / cost。
	 */
	private void updateDailyUsage(String entCode, String model,
			int promptTokens, int completionTokens, int totalTokens, BigDecimal cost) {
		String userId = TenantContext.getUserIdOrDefault();
		erp.update(
			"INSERT INTO a_token_usage_daily " +
			"(ent_code, user_id, usage_date, model, request_count, " +
			"total_prompt_tokens, total_completion_tokens, total_tokens, total_tool_calls, estimated_cost) " +
			"VALUES (?, ?, CURDATE(), ?, 1, ?, ?, ?, 0, ?) " +
			"ON DUPLICATE KEY UPDATE " +
			"request_count = request_count + 1, " +
			"total_prompt_tokens = total_prompt_tokens + VALUES(total_prompt_tokens), " +
			"total_completion_tokens = total_completion_tokens + VALUES(total_completion_tokens), " +
			"total_tokens = total_tokens + VALUES(total_tokens), " +
			"estimated_cost = estimated_cost + VALUES(estimated_cost), " +
			"updated_at = NOW()",
			entCode, userId, model, promptTokens, completionTokens, totalTokens, cost);
	}

}
