package com.example.rag.chat.rag;

import java.util.List;

import com.example.rag.vo.ChatVO;

import org.springframework.ai.document.Document;

/**
 * 受管 RAG 回答结果。
 *
 * @param answer          回答文本
 * @param knowledgeBaseId 实际知识库 ID
 * @param documents       本轮合格召回分片
 * @param citations       经白名单验证的引用
 */
public record RagAnswerResult(String answer, String knowledgeBaseId,
		List<Document> documents, List<ChatVO.CitationResponse> citations) {
}
