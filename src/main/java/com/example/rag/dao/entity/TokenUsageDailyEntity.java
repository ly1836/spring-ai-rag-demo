package com.example.rag.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 每日 token 用量统计表实体。
 */
@Data
@TableName("a_token_usage_daily")
public class TokenUsageDailyEntity {

	/** 主键 ID。*/
	@TableId(type = IdType.AUTO)
	private Long id;

	/** 租户编码。*/
	private String entCode;

	/** 用户 ID。*/
	private String userId;

	/** 统计日期。*/
	private LocalDate usageDate;

	/** 模型名称。*/
	private String model;

	/** 请求次数。*/
	private Integer requestCount;

	/** 累计输入 token 数。*/
	private Integer totalPromptTokens;

	/** 累计输出 token 数。*/
	private Integer totalCompletionTokens;

	/** 累计。token 数。*/
	private Integer totalTokens;

	/** 累计工具调用次数。*/
	private Integer totalToolCalls;

	/** 估算费用。*/
	private BigDecimal estimatedCost;

	/** 创建时间。*/
	private LocalDateTime createdAt;

	/** 更新时间。*/
	private LocalDateTime updatedAt;

}
