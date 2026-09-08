package com.example.rag.dao.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 租户知识库实体。
 */
@Data
@TableName("a_knowledge_base")
public class KnowledgeBaseEntity {

	/** 主键 ID。 */
	@TableId(type = IdType.AUTO)
	private Long id;

	/** 知识库 ID。 */
	private String knowledgeBaseId;

	/** 租户编码。 */
	private String entCode;

	/** 知识库名称。 */
	private String name;

	/** 知识库说明。 */
	private String description;

	/** 状态：active / inactive / deleted。 */
	private String status;

	/** 是否为默认知识库。 */
	private Boolean isDefault;

	/** 创建人用户 ID。 */
	private String createdBy;

	/** 创建时间。 */
	private LocalDateTime createdAt;

	/** 更新时间。 */
	private LocalDateTime updatedAt;

}
