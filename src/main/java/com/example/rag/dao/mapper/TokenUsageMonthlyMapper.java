package com.example.rag.dao.mapper;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.rag.dao.entity.TokenUsageMonthlyEntity;
import com.example.rag.vo.BillingVO;
import org.apache.ibatis.annotations.Select;

/**
 * 每月 token 用量统计。Mapper。
 */
public interface TokenUsageMonthlyMapper extends BaseMapper<TokenUsageMonthlyEntity> {

	/**
	 * 查询当前租户最。12 个月用量。
	 *
	 * @return 月度用量列表
	 */
	@Select("""
		SELECT usage_month AS usageMonth, model, request_count AS requestCount,
		total_prompt_tokens AS totalPromptTokens, total_completion_tokens AS totalCompletionTokens,
		total_tokens AS totalTokens, active_users AS activeUsers, estimated_cost AS estimatedCost
		FROM a_token_usage_monthly ORDER BY usage_month DESC LIMIT 12
		""")
	List<BillingVO.MonthlyUsageItemResponse> selectMonthlyUsage();
}
