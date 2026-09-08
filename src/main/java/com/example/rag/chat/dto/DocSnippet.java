package com.example.rag.chat.dto;

/**
 * 文档搜索结果片段。
 *
 * @param text            文档文本
 * @param source          文档来源
 * @param score           相似度分数
 * @param documentId      稳定文档 ID
 * @param documentVersion 文档版本
 * @param chunkId         稳定分片 ID
 * @param chunkIndex      分片顺序
 */
public record DocSnippet(String text, String source, Double score, String documentId,
		int documentVersion, String chunkId, int chunkIndex) {
}
