package com.example.rag.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 月度账单表实体。
 */
@Data
@TableName("a_billing_invoice")
public class BillingInvoiceEntity {

	/** 主键 ID。*/
	@TableId(type = IdType.AUTO)
	private Long id;

	/** 账单编号。*/
	private String invoiceNo;

	/** 租户编码。*/
	private String entCode;

	/** 账单月份，格。yyyy-MM。*/
	private String billingMonth;

	/** 套餐编码。*/
	private String planCode;

	/** 套餐费用。*/
	private BigDecimal planFee;

	/** token 用量费用。*/
	private BigDecimal tokenUsageFee;

	/** 账单总金额。*/
	private BigDecimal totalAmount;

	/** 月度。token 用量。*/
	private Long totalTokens;

	/** 月度总请求数。*/
	private Integer totalRequests;

	/** 账单状态：pending / paid / overdue。*/
	private String status;

	/** 支付时间。*/
	private LocalDateTime paidAt;

	/** 创建时间。*/
	private LocalDateTime createdAt;

	/** 更新时间。*/
	private LocalDateTime updatedAt;

}
