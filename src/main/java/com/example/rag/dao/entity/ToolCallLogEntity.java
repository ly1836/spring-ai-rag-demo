package com.example.rag.dao.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * LLM Tool 命中流水表实体。
 */
@Data
@TableName("a_tool_call_log")
public class ToolCallLogEntity {

	/** 主键 ID。*/
	@TableId(type = IdType.AUTO)
	private Long id;

	/** 单次问答链路 ID。*/
	private String traceId;

	/** 会话 ID。*/
	private String conversationId;

	/** 助手消息 ID，异步补齐时使用。*/
	private String messageId;

	/** 租户编码。*/
	private String entCode;

	/** 用户 ID。*/
	private String userId;

	/** 问答模式。*/
	private String mode;

	/** 使用模型。*/
	private String model;

	/** Tool 名称。*/
	private String toolName;

	/** Tool 来源类型：code / database。*/
	private String toolType;

	/** Tool 入参 JSON。*/
	private String argumentsJson;

	/** 返回结果条数。*/
	private Integer resultCount;

	/** 调用耗时，单位毫秒。*/
	private Long durationMs;

	/** 调用状态：success / error。*/
	private String status;

	/** 错误信息。*/
	private String errorMessage;

	/** 创建时间。*/
	private LocalDateTime createdAt;

}
