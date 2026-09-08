package com.example.rag.chat.rag;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.rag.config.TenantContext;
import com.example.rag.knowledge.KnowledgeDocumentService;
import com.example.rag.knowledge.dto.DocumentVersionKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.ai.document.Document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 受管文档资格过滤器测试。
 */
class RagDocumentEligibilityFilterTest {

	/**
	 * 清理测试租户上下文。
	 */
	@AfterEach
	public void tearDown() {
		TenantContext.clear();
	}

	/**
	 * 验证只保留当前租户、知识库和 ready 版本，并按 topK 截断。
	 */
	@Test
	public void shouldKeepOnlyReadyManagedDocumentsInCurrentScope() {
		KnowledgeDocumentService documentService = mock(KnowledgeDocumentService.class);
		RagDocumentEligibilityFilter filter = new RagDocumentEligibilityFilter(documentService);
		TenantContext.setEntCode("ENT001");
		Document ready = document("ready", "ENT001", "kb-1", "doc-1", 2);
		Document processing = document("processing", "ENT001", "kb-1", "doc-2", 3);
		Document crossTenant = document("cross", "ENT002", "kb-1", "doc-1", 2);
		Document crossKnowledgeBase = document("cross-kb", "ENT001", "kb-2", "doc-1", 2);
		Document secondReady = document("ready-2", "ENT001", "kb-1", "doc-3", 1);
		Set<DocumentVersionKey> requested = Set.of(new DocumentVersionKey("doc-1", 2),
			new DocumentVersionKey("doc-2", 3), new DocumentVersionKey("doc-3", 1));
		when(documentService.findReadyVersionSources(eq("kb-1"), eq(requested)))
			.thenReturn(Map.of(new DocumentVersionKey("doc-1", 2), "manual.txt",
				new DocumentVersionKey("doc-3", 1), "manual.txt"));

		List<Document> result = filter.filter("kb-1",
			List.of(ready, processing, crossTenant, crossKnowledgeBase, secondReady), 1);

		assertThat(result).containsExactly(ready);
	}

	/**
	 * 验证缺少稳定文档版本元数据的旧向量不会参与数据库资格查询。
	 */
	@Test
	public void shouldExcludeLegacyVectorsWithoutManagedMetadata() {
		KnowledgeDocumentService documentService = mock(KnowledgeDocumentService.class);
		RagDocumentEligibilityFilter filter = new RagDocumentEligibilityFilter(documentService);
		TenantContext.setEntCode("ENT001");
		Document legacy = new Document("旧向量", Map.of("ent_code", "ENT001",
			"knowledge_base_id", "kb-1", "source", "legacy.txt"));
		when(documentService.findReadyVersionSources("kb-1", Set.of())).thenReturn(Map.of());

		List<Document> result = filter.filter("kb-1", List.of(legacy), 5);

		assertThat(result).isEmpty();
	}

	/**
	 * 创建测试用受管文档分片。
	 *
	 * @param text            文本
	 * @param entCode         租户编码
	 * @param knowledgeBaseId 知识库 ID
	 * @param documentId      文档 ID
	 * @param version         文档版本
	 * @return 文档分片
	 */
	private Document document(String text, String entCode, String knowledgeBaseId,
			String documentId, int version) {
		return document(text, entCode, knowledgeBaseId, documentId, version,
			"manual.txt", documentId + "-chunk", 0);
	}

	/**
	 * 创建可指定来源和分片身份的测试文档。
	 *
	 * @param text            文本
	 * @param entCode         租户编码
	 * @param knowledgeBaseId 知识库 ID
	 * @param documentId      文档 ID
	 * @param version         文档版本
	 * @param source          来源名称
	 * @param chunkId         分片 ID
	 * @param chunkIndex      分片顺序
	 * @return 文档分片
	 */
	private Document document(String text, String entCode, String knowledgeBaseId,
			String documentId, int version, String source, String chunkId, int chunkIndex) {
		return new Document(text, Map.of("ent_code", entCode, "knowledge_base_id", knowledgeBaseId,
			"document_id", documentId, "document_version", version,
			"chunk_id", chunkId, "chunk_index", chunkIndex, "source", source,
			EmbeddingModelMetadata.METADATA_KEY, EmbeddingModelMetadata.CURRENT_MODEL_ID));
	}

	/**
	 * 验证缺少分片身份、分片顺序非法或来源不一致的向量都会被排除。
	 */
	@Test
	public void shouldExcludeIncompleteOrMismatchedChunkIdentity() {
		KnowledgeDocumentService documentService = mock(KnowledgeDocumentService.class);
		RagDocumentEligibilityFilter filter = new RagDocumentEligibilityFilter(documentService);
		TenantContext.setEntCode("ENT001");
		Document missingChunkId = new Document("缺少分片", Map.of(
			"ent_code", "ENT001", "knowledge_base_id", "kb-1", "document_id", "doc-1",
			"document_version", 1, "chunk_index", 0, "source", "manual.txt"));
		Document invalidChunkIndex = new Document("非法顺序", Map.of(
			"ent_code", "ENT001", "knowledge_base_id", "kb-1", "document_id", "doc-2",
			"document_version", 1, "chunk_id", "chunk-2", "chunk_index", -1,
			"source", "manual.txt"));
		Document mismatchedSource = new Document("来源不一致", Map.of(
			"ent_code", "ENT001", "knowledge_base_id", "kb-1", "document_id", "doc-3",
			"document_version", 1, "chunk_id", "chunk-3", "chunk_index", 0,
			"source", "other.txt"));
		when(documentService.findReadyVersionSources("kb-1",
			Set.of(new DocumentVersionKey("doc-3", 1))))
			.thenReturn(Map.of(new DocumentVersionKey("doc-3", 1), "manual.txt"));

		List<Document> result = filter.filter("kb-1",
			List.of(missingChunkId, invalidChunkIndex, mismatchedSource), 5);

		assertThat(result).isEmpty();
	}

	/**
	 * 验证多个合格文档存在时先保留各文档首条候选，再按原排序补齐。
	 */
	@Test
	public void shouldDiversifyEligibleCandidatesBeforeFillingByOriginalOrder() {
		KnowledgeDocumentService documentService = mock(KnowledgeDocumentService.class);
		RagDocumentEligibilityFilter filter = new RagDocumentEligibilityFilter(documentService);
		TenantContext.setEntCode("ENT001");
		Document largeFirst = document("大文档第一条", "ENT001", "kb-1", "doc-large", 1,
			"large.pdf", "large-1", 0);
		Document largeSecond = document("大文档第二条", "ENT001", "kb-1", "doc-large", 1,
			"large.pdf", "large-2", 1);
		Document smallFirst = document("小文档第一条", "ENT001", "kb-1", "doc-small", 1,
			"渠道技术方案.docx", "small-1", 0);
		Document largeThird = document("大文档第三条", "ENT001", "kb-1", "doc-large", 1,
			"large.pdf", "large-3", 2);
		Set<DocumentVersionKey> requested = Set.of(
			new DocumentVersionKey("doc-large", 1), new DocumentVersionKey("doc-small", 1));
		when(documentService.findReadyVersionSources("kb-1", requested)).thenReturn(Map.of(
			new DocumentVersionKey("doc-large", 1), "large.pdf",
			new DocumentVersionKey("doc-small", 1), "渠道技术方案.docx"));

		List<Document> result = filter.filter("kb-1",
			List.of(largeFirst, largeSecond, smallFirst, largeThird), 3);

		assertThat(result).containsExactly(largeFirst, smallFirst, largeSecond);
	}

	/**
	 * 验证旧嵌入模型生成的完整受管向量仍会被排除。
	 */
	@Test
	public void shouldExcludeVectorsFromPreviousEmbeddingModel() {
		KnowledgeDocumentService documentService = mock(KnowledgeDocumentService.class);
		RagDocumentEligibilityFilter filter = new RagDocumentEligibilityFilter(documentService);
		TenantContext.setEntCode("ENT001");
		Document oldModelDocument = new Document("旧模型向量", Map.of(
			"ent_code", "ENT001", "knowledge_base_id", "kb-1", "document_id", "doc-1",
			"document_version", 1, "chunk_id", "chunk-1", "chunk_index", 0,
			"source", "manual.txt", EmbeddingModelMetadata.METADATA_KEY,
			"all-MiniLM-L6-v2"));
		when(documentService.findReadyVersionSources("kb-1",
			Set.of(new DocumentVersionKey("doc-1", 1))))
			.thenReturn(Map.of(new DocumentVersionKey("doc-1", 1), "manual.txt"));

		List<Document> result = filter.filter("kb-1", List.of(oldModelDocument), 5);

		assertThat(result).isEmpty();
	}

}
