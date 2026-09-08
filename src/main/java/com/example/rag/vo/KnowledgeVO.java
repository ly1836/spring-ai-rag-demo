package com.example.rag.vo;

import java.util.List;
import java.util.Objects;

/**
 * 知识库管理模块请求与响应对象。
 */
public final class KnowledgeVO {

	/**
	 * 禁止实例化知识库 VO 容器。
	 */
	private KnowledgeVO() {
	}

	/**
	 * 创建知识库请求。
	 *
	 * @param name        知识库名称
	 * @param description 知识库说明
	 */
	public record CreateKnowledgeBaseRequest(String name, String description) {
		public CreateKnowledgeBaseRequest {
			name = Objects.requireNonNullElse(name, "");
			description = Objects.requireNonNullElse(description, "");
		}
	}

	/**
	 * 更新知识库请求。
	 *
	 * @param name        可空知识库名称
	 * @param description 可空知识库说明
	 * @param status      可空状态
	 * @param isDefault   可空默认标记
	 */
	public record UpdateKnowledgeBaseRequest(String name, String description, String status, Boolean isDefault) {
	}

	/**
	 * 知识库列表项。
	 *
	 * @param knowledgeBaseId 知识库 ID
	 * @param name            知识库名称
	 * @param description     知识库说明
	 * @param status          状态
	 * @param isDefault       是否默认知识库
	 * @param documentCount   稳定文档数量
	 * @param createdAt       创建时间
	 * @param updatedAt       更新时间
	 */
	public record KnowledgeBaseItem(String knowledgeBaseId, String name, String description,
			String status, boolean isDefault, int documentCount, String createdAt, String updatedAt) {
	}

	/**
	 * 知识库列表响应。
	 *
	 * @param data 知识库列表
	 */
	public record KnowledgeBaseListResponse(List<KnowledgeBaseItem> data) {
	}

	/**
	 * 知识库删除响应。
	 *
	 * @param knowledgeBaseId 已删除知识库 ID
	 */
	public record DeleteKnowledgeBaseResponse(String knowledgeBaseId) {
	}

	/**
	 * 文档列表项。
	 *
	 * @param documentId     稳定文档 ID
	 * @param knowledgeBaseId 知识库 ID
	 * @param sourceName     来源名称
	 * @param contentType    内容类型
	 * @param sizeBytes      文件大小
	 * @param version        最新版本号
	 * @param status         最新版本状态
	 * @param readyVersion   当前可检索版本号
	 * @param requiresReindex 当前可用版本是否需要按新模型重新导入
	 * @param chunkCount     最新版本分片数
	 * @param errorMessage   安全错误摘要
	 * @param createdAt      创建时间
	 * @param updatedAt      更新时间
	 */
	public record KnowledgeDocumentItem(String documentId, String knowledgeBaseId, String sourceName,
			String contentType, Long sizeBytes, int version, String status, Integer readyVersion,
			boolean requiresReindex, int chunkCount, String errorMessage, String createdAt, String updatedAt) {
	}

	/**
	 * 文档列表响应。
	 *
	 * @param knowledgeBaseId 知识库 ID
	 * @param data            文档列表
	 */
	public record KnowledgeDocumentListResponse(String knowledgeBaseId, List<KnowledgeDocumentItem> data) {
	}

	/**
	 * 文档导入响应。
	 *
	 * @param documentId     稳定文档 ID
	 * @param knowledgeBaseId 知识库 ID
	 * @param sourceName     来源名称
	 * @param version        新版本号
	 * @param status         最终状态
	 * @param chunksLoaded   入库分片数量
	 */
	public record KnowledgeDocumentImportResponse(String documentId, String knowledgeBaseId,
			String sourceName, int version, String status, int chunksLoaded) {
	}

	/**
	 * 文档删除响应。
	 *
	 * @param documentId 已删除稳定文档 ID
	 */
	public record DeleteKnowledgeDocumentResponse(String documentId) {
	}

}
