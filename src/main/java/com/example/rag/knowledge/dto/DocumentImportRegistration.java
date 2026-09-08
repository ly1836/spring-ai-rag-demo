package com.example.rag.knowledge.dto;

/**
 * 文档导入登记结果。
 *
 * @param documentId     稳定文档 ID
 * @param knowledgeBaseId 知识库 ID
 * @param sourceName     来源名称
 * @param version        新版本号
 */
public record DocumentImportRegistration(String documentId, String knowledgeBaseId,
		String sourceName, int version) {
}
