package com.example.rag.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 计费套餐表实体。
 */
@Data
@TableName("a_billing_plan")
public class BillingPlanEntity {

	/** 主键 ID。*/
	@TableId(type = IdType.AUTO)
	private Long id;

	/** 套餐编码。*/
	private String planCode;

	/** 套餐名称。*/
	private String planName;

	/** 套餐类型：free / basic / pro / enterprise。*/
	private String planType;

	/** 月度 token 配额。*/
	private Long monthlyTokenQuota;

	/** 月费。*/
	private BigDecimal monthlyPrice;

	/** 超额每千 token 单价。*/
	private BigDecimal overagePricePer1k;

	/** 每日最大会话数。*/
	private Integer maxConversationsPerDay;

	/** 单次请求最。token 数。*/
	private Integer maxTokensPerRequest;

	/** 最大用户数。*/
	private Integer maxUsers;

	/** 套餐功能 JSON。*/
	private String features;

	/** 套餐状态：active / discontinued。*/
	private String status;

	/** 创建时间。*/
	private LocalDateTime createdAt;

	/** 更新时间。*/
	private LocalDateTime updatedAt;

}
