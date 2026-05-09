package com.example.rag.dao.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 对话会话表实体。
 */
@Data
@TableName("a_chat_conversation")
public class ChatConversationEntity {

	/** 主键 ID。*/
	@TableId(type = IdType.AUTO)
	private Long id;

	/** 会话 ID。*/
	private String conversationId;

	/** 租户编码。*/
	private String entCode;

	/** 用户 ID。*/
	private String userId;

	/** 会话标题。*/
	private String title;

	/** 问答模式：auto / data / knowledge。*/
	private String mode;

	/** 消息总数。*/
	private Integer messageCount;

	/** 会话累计 token 数。*/
	private Integer totalTokens;

	/** 会话状态：active / archived / deleted。*/
	private String status;

	/** 创建时间。*/
	private LocalDateTime createdAt;

	/** 更新时间。*/
	private LocalDateTime updatedAt;

}
