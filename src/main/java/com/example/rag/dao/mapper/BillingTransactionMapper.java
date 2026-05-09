package com.example.rag.dao.mapper;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.rag.dao.entity.BillingTransactionEntity;
import com.example.rag.vo.BillingVO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 计费交易流水。Mapper。
 */
public interface BillingTransactionMapper extends BaseMapper<BillingTransactionEntity> {

	/**
	 * 查询当前租户交易流水。
	 *
	 * @param size   每页数量
	 * @param offset 偏移。
	 * @return 交易流水列表
	 */
	@Select("""
		SELECT transaction_no AS transactionNo, type, amount, balance_after AS balanceAfter,
		token_count AS tokenCount, model, conversation_id AS conversationId,
		description, operator, DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS createdAt
		FROM a_billing_transaction
		ORDER BY created_at DESC LIMIT #{size} OFFSET #{offset}
		""")
	List<BillingVO.TransactionItemResponse> selectTransactions(
			@Param("size") int size, @Param("offset") int offset);
}
