package com.example.rag.chat.rag;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.stereotype.Component;

/**
 * 从同一轮模型响应中提取 RAG 实际使用的证据分片。
 */
@Component
public class RagEvidenceExtractor {

	/**
	 * 读取模块化 RAG 或旧版问答 Advisor 写入的文档元数据。
	 *
	 * @param response 模型响应
	 * @return 本轮实际使用的文档分片
	 */
	public List<Document> extract(ChatResponse response) {
		if (response == null || response.getMetadata() == null) {
			return List.of();
		}
		List<Document> documents = extractDocuments(
			response.getMetadata().get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT));
		if (!documents.isEmpty()) {
			return documents;
		}
		return extractDocuments(response.getMetadata().get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS));
	}

	/**
	 * 将未知元数据安全转换为文档列表。
	 *
	 * @param value Advisor 写入的元数据值
	 * @return 仅包含 Document 的不可变列表
	 */
	private List<Document> extractDocuments(Object value) {
		if (!(value instanceof List<?> values)) {
			return List.of();
		}
		List<Document> documents = new ArrayList<>();
		for (Object item : values) {
			if (item instanceof Document document) {
				documents.add(document);
			}
		}
		return List.copyOf(documents);
	}

}
