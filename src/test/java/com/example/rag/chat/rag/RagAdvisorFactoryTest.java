package com.example.rag.chat.rag;

import java.util.List;

import com.example.rag.config.TenantContext;
import com.example.rag.dao.entity.KnowledgeBaseEntity;
import com.example.rag.knowledge.KnowledgeBaseService;
import com.example.rag.knowledge.KnowledgeDocumentService;
import com.example.rag.knowledge.dto.ManagedDocumentMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 生产 RAG Advisor 工厂测试。
 */
class RagAdvisorFactoryTest {

	/**
	 * 清理测试租户上下文。
	 */
	@AfterEach
	public void tearDown() {
		TenantContext.clear();
	}

	/**
	 * 验证检索器按知识库过采样且每次调用只访问一次向量库。
	 */
	@Test
	public void shouldBuildScopedOversampledRetrieverWithSingleVectorCall() {
		VectorStore vectorStore = mock(VectorStore.class);
		KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
		KnowledgeDocumentService knowledgeDocumentService = mock(KnowledgeDocumentService.class);
		RagDocumentEligibilityFilter eligibilityFilter = mock(RagDocumentEligibilityFilter.class);
		TaskExecutor taskExecutor = new SyncTaskExecutor();
		RagAdvisorFactory factory = new RagAdvisorFactory(vectorStore, knowledgeBaseService,
			knowledgeDocumentService, eligibilityFilter, new RagContextFormatter(), taskExecutor);
		KnowledgeBaseEntity knowledgeBase = new KnowledgeBaseEntity();
		knowledgeBase.setKnowledgeBaseId("kb-1");
		TenantContext.setEntCode("ENT001");
		when(knowledgeBaseService.resolveActiveKnowledgeBase(null)).thenReturn(knowledgeBase);
		when(knowledgeDocumentService.findReadyDocuments("kb-1")).thenReturn(List.of());
		Document document = new Document("证据");
		when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(document));
		when(eligibilityFilter.filter(eq("kb-1"), anyList(), eq(5)))
			.thenAnswer(invocation -> invocation.getArgument(1));
		ArgumentCaptor<SearchRequest> requestCaptor = ArgumentCaptor.forClass(SearchRequest.class);

		RagRequestContext context = factory.create(null, "用户原始问题", 5, 0.25);
		List<Document> result = context.documentRetriever()
			.retrieve(Query.builder().text("业务守卫增强后的问题").build());

		assertThat(result).containsExactly(document);
		assertThat(context.knowledgeBaseId()).isEqualTo("kb-1");
		assertThat(ReflectionTestUtils.getField(context.advisor(), "taskExecutor"))
			.isSameAs(taskExecutor);
		verify(vectorStore).similaritySearch(requestCaptor.capture());
		assertThat(requestCaptor.getValue().getTopK()).isEqualTo(15);
		assertThat(requestCaptor.getValue().getSimilarityThreshold()).isEqualTo(0.25);
		// 模型使用增强提示时，向量请求仍必须保持用户原始问题。
		assertThat(requestCaptor.getValue().getQuery()).isEqualTo("用户原始问题");
		assertThat(requestCaptor.getValue().getFilterExpression().toString())
			.contains("ent_code", "ENT001", "knowledge_base_id", "kb-1",
				EmbeddingModelMetadata.METADATA_KEY, EmbeddingModelMetadata.CURRENT_MODEL_ID);
	}

	/**
	 * 验证问题明确包含唯一 ready 文档标题时，向量查询限定对应来源。
	 */
	@Test
	public void shouldRestrictRetrievalToUniquelyMatchedReadyDocumentTitle() {
		VectorStore vectorStore = mock(VectorStore.class);
		KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
		KnowledgeDocumentService knowledgeDocumentService = mock(KnowledgeDocumentService.class);
		RagDocumentEligibilityFilter eligibilityFilter = mock(RagDocumentEligibilityFilter.class);
		RagAdvisorFactory factory = new RagAdvisorFactory(vectorStore, knowledgeBaseService,
			knowledgeDocumentService, eligibilityFilter, new RagContextFormatter(),
			new SyncTaskExecutor());
		KnowledgeBaseEntity knowledgeBase = new KnowledgeBaseEntity();
		knowledgeBase.setKnowledgeBaseId("kb-1");
		TenantContext.setEntCode("ENT001");
		when(knowledgeBaseService.resolveActiveKnowledgeBase("kb-1")).thenReturn(knowledgeBase);
		when(knowledgeDocumentService.findReadyDocuments("kb-1")).thenReturn(List.of(
			new ManagedDocumentMetadata("ENT001", "kb-1", "doc-channel", 1,
				"渠道技术方案.docx"),
			new ManagedDocumentMetadata("ENT001", "kb-1", "doc-java", 1,
				"字节大佬总结的资料.pdf")));
		Document document = new Document("渠道技术方案证据");
		when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(document));
		when(eligibilityFilter.filter(eq("kb-1"), anyList(), eq(8)))
			.thenAnswer(invocation -> invocation.getArgument(1));
		ArgumentCaptor<SearchRequest> requestCaptor = ArgumentCaptor.forClass(SearchRequest.class);

		RagRequestContext context = factory.create("kb-1", "请介绍下 渠道技术方案！", 8, 0.25);
		context.documentRetriever().retrieve(Query.builder().text("模型增强后的问题").build());

		verify(vectorStore).similaritySearch(requestCaptor.capture());
		assertThat(requestCaptor.getValue().getTopK()).isEqualTo(24);
		assertThat(requestCaptor.getValue().getQuery()).isEqualTo("请介绍下 渠道技术方案！");
		assertThat(requestCaptor.getValue().getFilterExpression().toString())
			.contains("source", "渠道技术方案.docx",
				EmbeddingModelMetadata.METADATA_KEY, EmbeddingModelMetadata.CURRENT_MODEL_ID);
	}

	/**
	 * 验证空格拆开的产品名保留原问题并追加紧凑别名。
	 */
	@Test
	public void shouldAppendCompactAliasForSpacedProductName() {
		VectorStore vectorStore = mock(VectorStore.class);
		KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
		KnowledgeDocumentService knowledgeDocumentService = mock(KnowledgeDocumentService.class);
		RagDocumentEligibilityFilter eligibilityFilter = mock(RagDocumentEligibilityFilter.class);
		RagAdvisorFactory factory = new RagAdvisorFactory(vectorStore, knowledgeBaseService,
			knowledgeDocumentService, eligibilityFilter, new RagContextFormatter(),
			new SyncTaskExecutor());
		KnowledgeBaseEntity knowledgeBase = new KnowledgeBaseEntity();
		knowledgeBase.setKnowledgeBaseId("kb-1");
		TenantContext.setEntCode("ENT001");
		when(knowledgeBaseService.resolveActiveKnowledgeBase("kb-1")).thenReturn(knowledgeBase);
		when(knowledgeDocumentService.findReadyDocuments("kb-1")).thenReturn(List.of());
		when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
		when(eligibilityFilter.filter(eq("kb-1"), anyList(), eq(5))).thenReturn(List.of());
		ArgumentCaptor<SearchRequest> requestCaptor = ArgumentCaptor.forClass(SearchRequest.class);

		RagRequestContext context = factory.create("kb-1", "说一下rabbit mq", 5, 0.25);
		context.documentRetriever().retrieve(Query.builder().text("模型增强后的问题").build());

		verify(vectorStore).similaritySearch(requestCaptor.capture());
		assertThat(requestCaptor.getValue().getQuery()).isEqualTo("说一下rabbit mq rabbitmq");
	}

	/**
	 * 验证无效或跨租户知识库在向量访问前被拒绝。
	 */
	@Test
	public void shouldRejectInvalidKnowledgeBaseBeforeVectorAccess() {
		VectorStore vectorStore = mock(VectorStore.class);
		KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
		RagAdvisorFactory factory = new RagAdvisorFactory(vectorStore, knowledgeBaseService,
			mock(KnowledgeDocumentService.class), mock(RagDocumentEligibilityFilter.class),
			new RagContextFormatter(),
			new SyncTaskExecutor());
		TenantContext.setEntCode("ENT001");
		when(knowledgeBaseService.resolveActiveKnowledgeBase("other-kb"))
			.thenThrow(new IllegalStateException("知识库不存在"));

		assertThatThrownBy(() -> factory.create("other-kb", "问题", 5, 0.25))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("知识库不存在");
		verifyNoInteractions(vectorStore);
	}

	/**
	 * 验证超过候选窗口的 topK 在访问知识库和向量库前被明确拒绝。
	 */
	@Test
	public void shouldRejectTopKAboveManagedRetrievalLimit() {
		VectorStore vectorStore = mock(VectorStore.class);
		KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
		RagAdvisorFactory factory = new RagAdvisorFactory(vectorStore, knowledgeBaseService,
			mock(KnowledgeDocumentService.class), mock(RagDocumentEligibilityFilter.class),
			new RagContextFormatter(),
			new SyncTaskExecutor());

		assertThatThrownBy(() -> factory.create(null, "问题", 25, 0.25))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("RAG topK 不能超过 24");
		verifyNoInteractions(knowledgeBaseService, vectorStore);
	}

}
