package com.example.rag.chat.dto;

import java.util.List;

/**
 * 受管知识库搜索结果。
 *
 * @param knowledgeBaseId 实际知识库 ID
 * @param documents       合格文档分片
 */
public record RagSearchResult(String knowledgeBaseId, List<DocSnippet> documents) {
}
