package com.example.rag.knowledge.dto;

/**
 * 受管文档写入向量库所需身份。
 *
 * @param entCode         租户编码
 * @param knowledgeBaseId 知识库 ID
 * @param documentId      稳定文档 ID
 * @param documentVersion 文档版本号
 * @param sourceName      来源名称
 */
public record ManagedDocumentMetadata(String entCode, String knowledgeBaseId,
		String documentId, int documentVersion, String sourceName) {
}
