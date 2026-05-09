package com.example.rag.dao.mapper;

import java.math.BigDecimal;
import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.rag.dao.entity.TokenUsageDailyEntity;
import com.example.rag.vo.BillingVO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 每日 token 用量统计。Mapper。
 */
public interface TokenUsageDailyMapper extends BaseMapper<TokenUsageDailyEntity> {

	/**
	 * 查询当前租户日期范围内每日用量。
	 *
	 * @param startDate 开始日。
	 * @param endDate   结束日期
	 * @return 每日用量列表
	 */
	@Select("""
		SELECT user_id AS userId, DATE_FORMAT(usage_date, '%Y-%m-%d') AS usageDate,
		model, request_count AS requestCount,
		total_prompt_tokens AS totalPromptTokens, total_completion_tokens AS totalCompletionTokens,
		total_tokens AS totalTokens, total_tool_calls AS totalToolCalls, estimated_cost AS estimatedCost
		FROM a_token_usage_daily WHERE usage_date BETWEEN #{startDate} AND #{endDate}
		ORDER BY usage_date DESC
		""")
	List<BillingVO.DailyUsageItemResponse> selectDailyUsage(
			@Param("startDate") String startDate, @Param("endDate") String endDate);

	/**
	 * 累加当前用户每日 token 用量。
	 *
	 * @param entCode          租户编码
	 * @param userId           用户 ID
	 * @param model            模型名称
	 * @param promptTokens     输入 token 。
	 * @param completionTokens 输出 token 。
	 * @param totalTokens      。token 。
	 * @param cost             估算费用
	 * @return 影响行数
	 */
	@Insert("""
		INSERT INTO a_token_usage_daily
		(ent_code, user_id, usage_date, model, request_count,
		total_prompt_tokens, total_completion_tokens, total_tokens, total_tool_calls, estimated_cost)
		VALUES (#{entCode}, #{userId}, CURDATE(), #{model}, 1, #{promptTokens}, #{completionTokens}, #{totalTokens}, 0, #{cost})
		ON DUPLICATE KEY UPDATE
		request_count = request_count + 1,
		total_prompt_tokens = total_prompt_tokens + VALUES(total_prompt_tokens),
		total_completion_tokens = total_completion_tokens + VALUES(total_completion_tokens),
		total_tokens = total_tokens + VALUES(total_tokens),
		estimated_cost = estimated_cost + VALUES(estimated_cost),
		updated_at = NOW()
		""")
	int upsertDailyUsage(@Param("entCode") String entCode, @Param("userId") String userId,
			@Param("model") String model, @Param("promptTokens") int promptTokens,
			@Param("completionTokens") int completionTokens, @Param("totalTokens") int totalTokens,
			@Param("cost") BigDecimal cost);
}
