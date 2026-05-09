package com.example.rag.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 模型计价规则表实体。
 */
@Data
@TableName("a_billing_price_rule")
public class BillingPriceRuleEntity {

	/** 主键 ID。*/
	@TableId(type = IdType.AUTO)
	private Long id;

	/** 模型名称。*/
	private String model;

	/** 输入 token 每千单价。*/
	private BigDecimal inputPricePer1k;

	/** 输出 token 每千单价。*/
	private BigDecimal outputPricePer1k;

	/** 生效日期。*/
	private LocalDate effectiveDate;

	/** 失效日期，null 表示长期有效。*/
	private LocalDate expiredDate;

	/** 备注。*/
	private String remark;

	/** 创建时间。*/
	private LocalDateTime createdAt;

}
