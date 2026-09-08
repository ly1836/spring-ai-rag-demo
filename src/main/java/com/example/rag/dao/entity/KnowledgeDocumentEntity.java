package com.example.rag.dao.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 知识文档版本实体。
 */
@Data
@TableName("a_knowledge_document")
public class KnowledgeDocumentEntity {

	/** 主键 ID。 */
	@TableId(type = IdType.AUTO)
	private Long id;

	/** 稳定文档 ID。 */
	private String documentId;

	/** 所属知识库 ID。 */
	private String knowledgeBaseId;

	/** 租户编码。 */
	private String entCode;

	/** 文档来源名称。 */
	private String sourceName;

	/** 文件内容类型。 */
	private String contentType;

	/** 文件大小。 */
	private Long sizeBytes;

	/** 文件 SHA-256 摘要。 */
	private String checksumSha256;

	/** 成功入库使用的嵌入模型身份。 */
	private String embeddingModel;

	/** 文档版本号。 */
	private Integer version;

	/** 状态：processing / ready / failed / superseded / deleted。 */
	private String status;

	/** 实际入库分片数。 */
	private Integer chunkCount;

	/** 安全错误摘要。 */
	private String errorMessage;

	/** 创建人用户 ID。 */
	private String createdBy;

	/** 创建时间。 */
	private LocalDateTime createdAt;

	/** 更新时间。 */
	private LocalDateTime updatedAt;

}
