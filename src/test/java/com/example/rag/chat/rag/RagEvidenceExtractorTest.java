package com.example.rag.chat.rag;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 同轮 RAG 证据提取测试。
 */
class RagEvidenceExtractorTest {

	/**
	 * 验证优先读取模块化 RAG 元数据并忽略非文档元素。
	 */
	@Test
	public void shouldPreferModularRagMetadata() {
		RagEvidenceExtractor extractor = new RagEvidenceExtractor();
		Document modular = new Document("模块化证据");
		Document legacy = new Document("旧版证据");
		ChatResponse response = ChatResponse.builder()
			.generations(List.of())
			.metadata(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT, List.of(modular, "非法元素"))
			.metadata(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS, List.of(legacy))
			.build();

		assertThat(extractor.extract(response)).containsExactly(modular);
	}

	/**
	 * 验证模块化元数据缺失时兼容旧版 Advisor 元数据键。
	 */
	@Test
	public void shouldFallbackToLegacyAdvisorMetadata() {
		RagEvidenceExtractor extractor = new RagEvidenceExtractor();
		Document legacy = new Document("旧版证据");
		ChatResponse response = ChatResponse.builder()
			.generations(List.of())
			.metadata(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS, List.of(legacy))
			.build();

		assertThat(extractor.extract(response)).containsExactly(legacy);
	}

}
