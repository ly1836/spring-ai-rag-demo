package com.example.rag.dao.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 对话消息表实体。
 */
@Data
@TableName("a_chat_message")
public class ChatMessageEntity {

	/** 主键 ID。*/
	@TableId(type = IdType.AUTO)
	private Long id;

	/** 消息 ID。*/
	private String messageId;

	/** 所属会。ID。*/
	private String conversationId;

	/** 租户编码。*/
	private String entCode;

	/** 用户 ID。*/
	private String userId;

	/** 消息角色：user / assistant / system。*/
	private String role;

	/** 消息内容。*/
	private String content;

	/** 问答模式。*/
	private String mode;

	/** 使用的模型名称。*/
	private String model;

	/** 输入 token 数。*/
	private Integer promptTokens;

	/** 输出 token 数。*/
	private Integer completionTokens;

	/** 。token 数。*/
	private Integer totalTokens;

	/** 工具调用记录 JSON。*/
	private String toolCalls;

	/** 工具调用次数。*/
	private Integer toolCallsCount;

	/** 助手图表数据 JSON。*/
	private String chartSpec;

	/** RAG 检索文档数。*/
	private Integer ragDocCount;

	/** 响应耗时，单位毫秒。*/
	private Integer durationMs;

	/** 消息状态：success / cancelled / error / timeout。*/
	private String status;

	/** 错误信息。*/
	private String errorMessage;

	/** 创建时间。*/
	private LocalDateTime createdAt;

}
