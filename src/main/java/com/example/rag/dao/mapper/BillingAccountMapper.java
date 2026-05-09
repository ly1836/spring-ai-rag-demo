package com.example.rag.dao.mapper;

import java.math.BigDecimal;
import java.util.Map;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.rag.dao.entity.BillingAccountEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 租户计费账户。Mapper。
 */
public interface BillingAccountMapper extends BaseMapper<BillingAccountEntity> {

	/**
	 * 查询当前租户账户与套餐信息。
	 *
	 * @return 账户套餐信息
	 */
	@Select("""
		SELECT a.ent_code, a.plan_code, a.balance, a.total_recharged, a.total_consumed,
		a.used_tokens_this_month, a.billing_cycle_start, a.status,
		p.plan_name, p.plan_type, p.monthly_token_quota, p.monthly_price,
		p.max_conversations_per_day, p.max_tokens_per_request, p.max_users
		FROM a_billing_account a JOIN a_billing_plan p ON a.plan_code = p.plan_code
		LIMIT 1
		""")
	Map<String, Object> selectCurrentAccountWithPlan();

	/**
	 * 查询配额校验所需账户信息。
	 *
	 * @return 配额信息
	 */
	@Select("""
		SELECT a.balance, a.used_tokens_this_month, a.status,
		p.monthly_token_quota, p.plan_type
		FROM a_billing_account a JOIN a_billing_plan p ON a.plan_code = p.plan_code
		LIMIT 1
		""")
	Map<String, Object> selectCurrentQuotaInfo();

	/**
	 * 充值更新账户余额。
	 *
	 * @param amount 充值金。
	 * @return 影响行数
	 */
	@Update("""
		UPDATE a_billing_account SET balance = balance + #{amount},
		total_recharged = total_recharged + #{amount},
		status = 'active', updated_at = NOW()
		""")
	int recharge(@Param("amount") BigDecimal amount);

	/**
	 * 扣费更新账户余额。token 用量。
	 *
	 * @param cost        消费金额
	 * @param totalTokens token 总数
	 * @return 影响行数
	 */
	@Update("""
		UPDATE a_billing_account SET balance = balance - #{cost},
		total_consumed = total_consumed + #{cost},
		used_tokens_this_month = used_tokens_this_month + #{totalTokens},
		updated_at = NOW()
		""")
	int deduct(@Param("cost") BigDecimal cost, @Param("totalTokens") int totalTokens);

	/**
	 * 查询当前租户余额。
	 *
	 * @return 账户余额
	 */
	@Select("SELECT balance FROM a_billing_account LIMIT 1")
	BigDecimal selectCurrentBalance();
}
