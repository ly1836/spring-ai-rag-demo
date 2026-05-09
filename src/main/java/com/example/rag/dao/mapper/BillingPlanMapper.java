package com.example.rag.dao.mapper;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.rag.dao.entity.BillingPlanEntity;
import com.example.rag.vo.BillingVO;
import org.apache.ibatis.annotations.Select;

/**
 * 计费套餐。Mapper。
 */
public interface BillingPlanMapper extends BaseMapper<BillingPlanEntity> {

	/**
	 * 查询生效套餐列表。
	 *
	 * @return 套餐列表
	 */
	@Select("""
		SELECT plan_code AS planCode, plan_name AS planName, plan_type AS planType,
		monthly_token_quota AS monthlyTokenQuota, monthly_price AS monthlyPrice,
		overage_price_per_1k AS overagePricePer1k,
		max_conversations_per_day AS maxConversationsPerDay,
		max_tokens_per_request AS maxTokensPerRequest,
		max_users AS maxUsers, features
		FROM a_billing_plan WHERE status = 'active' ORDER BY monthly_price
		""")
	List<BillingVO.PlanItemResponse> selectActivePlans();
}
