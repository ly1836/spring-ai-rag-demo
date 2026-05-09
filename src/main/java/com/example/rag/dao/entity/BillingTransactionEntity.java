package com.example.rag.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 计费交易流水表实体。
 */
@Data
@TableName("a_billing_transaction")
public class BillingTransactionEntity {

	/** 主键 ID。*/
	@TableId(type = IdType.AUTO)
	private Long id;

	/** 交易流水号。*/
	private String transactionNo;

	/** 租户编码。*/
	private String entCode;

	/** 交易类型：recharge / deduction / refund / monthly_fee / gift。*/
	private String type;

	/** 交易金额，正数表示入账，负数表示扣除。*/
	private BigDecimal amount;

	/** 交易后余额。*/
	private BigDecimal balanceAfter;

	/** 关联 token 数。*/
	private Integer tokenCount;

	/** 关联模型名称。*/
	private String model;

	/** 关联会话 ID。*/
	private String conversationId;

	/** 交易描述。*/
	private String description;

	/** 操作人。*/
	private String operator;

	/** 创建时间。*/
	private LocalDateTime createdAt;

}
