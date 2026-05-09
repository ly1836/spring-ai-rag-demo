package com.example.rag.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 租户计费账户表实体。
 */
@Data
@TableName("a_billing_account")
public class BillingAccountEntity {

	/** 主键 ID。*/
	@TableId(type = IdType.AUTO)
	private Long id;

	/** 租户编码。*/
	private String entCode;

	/** 当前套餐编码。*/
	private String planCode;

	/** 账户余额。*/
	private BigDecimal balance;

	/** 累计充值金额。*/
	private BigDecimal totalRecharged;

	/** 累计消费金额。*/
	private BigDecimal totalConsumed;

	/** 本月已用 token 数。*/
	private Long usedTokensThisMonth;

	/** 当前计费周期起始日。*/
	private LocalDate billingCycleStart;

	/** 账户状态：active / suspended / arrears。*/
	private String status;

	/** 创建时间。*/
	private LocalDateTime createdAt;

	/** 更新时间。*/
	private LocalDateTime updatedAt;

}
