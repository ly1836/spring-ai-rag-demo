package com.example.rag.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 每月 token 用量统计表实体。
 */
@Data
@TableName("a_token_usage_monthly")
public class TokenUsageMonthlyEntity {

	/** 主键 ID。*/
	@TableId(type = IdType.AUTO)
	private Long id;

	/** 租户编码。*/
	private String entCode;

	/** 统计月份，格。yyyy-MM。*/
	private String usageMonth;

	/** 模型名称。*/
	private String model;

	/** 请求次数。*/
	private Integer requestCount;

	/** 累计输入 token 数。*/
	private Long totalPromptTokens;

	/** 累计输出 token 数。*/
	private Long totalCompletionTokens;

	/** 累计。token 数。*/
	private Long totalTokens;

	/** 活跃用户数。*/
	private Integer activeUsers;

	/** 估算费用。*/
	private BigDecimal estimatedCost;

	/** 创建时间。*/
	private LocalDateTime createdAt;

	/** 更新时间。*/
	private LocalDateTime updatedAt;

}
