package com.example.rag.dao.mapper;

import java.util.Map;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.rag.dao.entity.BillingPriceRuleEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 模型计价规则。Mapper。
 */
public interface BillingPriceRuleMapper extends BaseMapper<BillingPriceRuleEntity> {

	/**
	 * 查询当前生效的模型计价规则。
	 *
	 * @param model 模型名称
	 * @return 计价规则
	 */
	@Select("""
		SELECT input_price_per_1k, output_price_per_1k FROM a_billing_price_rule
		WHERE model = #{model} AND effective_date <= CURDATE()
		AND (expired_date IS NULL OR expired_date >= CURDATE())
		ORDER BY effective_date DESC LIMIT 1
		""")
	Map<String, Object> selectActiveRule(@Param("model") String model);
}
