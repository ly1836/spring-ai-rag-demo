package com.example.rag.knowledge.dto;

/**
 * 知识库稳定文档数量查询结果。
 *
 * @param knowledgeBaseId 知识库 ID
 * @param documentCount   未删除稳定文档数量
 */
public record KnowledgeDocumentCount(String knowledgeBaseId, Long documentCount) {
}
