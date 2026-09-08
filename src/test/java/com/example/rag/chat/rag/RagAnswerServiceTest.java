package com.example.rag.chat.rag;

import java.util.List;

import com.example.rag.vo.ChatVO;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 受管 RAG 回答服务测试。
 */
class RagAnswerServiceTest {

	/**
	 * 验证回答和引用复用响应中的同一批证据，不触发第二次检索。
	 */
	@Test
	public void shouldReuseResponseEvidenceWithoutSecondRetrieval() {
		RagAdvisorFactory advisorFactory = mock(RagAdvisorFactory.class);
		RagEvidenceExtractor extractor = mock(RagEvidenceExtractor.class);
		RagCitationValidator validator = mock(RagCitationValidator.class);
		DocumentRetriever retriever = mock(DocumentRetriever.class);
		RagAnswerService service = new RagAnswerService(advisorFactory, extractor, validator);
		RagRequestContext context = new RagRequestContext("kb-1", null, retriever);
		ChatResponse response = ChatResponse.builder().generations(List.of()).build();
		Document document = new Document("证据");
		List<Document> documents = List.of(document);
		List<ChatVO.CitationResponse> citations = List.of(new ChatVO.CitationResponse(
			1, "kb-1", "doc-1", 1, "chunk-1", 0, "manual.pdf", "证据", 0.9));
		when(extractor.extract(response)).thenReturn(documents);
		when(validator.validate("回答 [1]", "kb-1", documents)).thenReturn(citations);

		RagAnswerResult result = service.complete("回答 [1]", response, context);

		assertThat(result.documents()).containsExactly(document);
		assertThat(result.citations()).isEqualTo(citations);
		verifyNoInteractions(retriever);
	}

}
