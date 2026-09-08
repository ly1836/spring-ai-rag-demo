package com.example.rag.chat.rag;

import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;

/**
 * 单次受管 RAG 请求上下文。
 *
 * @param knowledgeBaseId 知识库 ID
 * @param advisor         模块化 RAG Advisor
 * @param documentRetriever 与 Advisor 共用的文档检索器
 */
public record RagRequestContext(String knowledgeBaseId, RetrievalAugmentationAdvisor advisor,
		DocumentRetriever documentRetriever) {
}
