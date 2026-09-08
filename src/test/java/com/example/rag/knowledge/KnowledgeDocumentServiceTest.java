package com.example.rag.knowledge;

import java.util.List;
import java.util.Set;

import com.example.rag.chat.rag.EmbeddingModelMetadata;
import com.example.rag.config.TenantContext;
import com.example.rag.dao.entity.KnowledgeBaseEntity;
import com.example.rag.dao.entity.KnowledgeDocumentEntity;
import com.example.rag.dao.mapper.KnowledgeDocumentMapper;
import com.example.rag.knowledge.dto.DocumentImportRegistration;
import com.example.rag.knowledge.dto.DocumentPromotionResult;
import com.example.rag.knowledge.dto.DocumentVersionKey;
import com.example.rag.knowledge.dto.ManagedDocumentMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识文档版本服务测试。
 */
class KnowledgeDocumentServiceTest {

	private KnowledgeBaseService knowledgeBaseService;

	private KnowledgeDocumentMapper knowledgeDocumentMapper;

	private KnowledgeDocumentService service;

	/**
	 * 初始化当前租户和测试依赖。
	 */
	@BeforeEach
	public void setUp() {
		this.knowledgeBaseService = mock(KnowledgeBaseService.class);
		this.knowledgeDocumentMapper = mock(KnowledgeDocumentMapper.class);
		this.service = new KnowledgeDocumentService(
			this.knowledgeBaseService, this.knowledgeDocumentMapper);
		TenantContext.setEntCode("ENT001");
		TenantContext.setUserId("U001");
		when(this.knowledgeBaseService.resolveActiveKnowledgeBaseForUpdate("kb-1"))
			.thenReturn(knowledgeBase());
		when(this.knowledgeBaseService.requireKnowledgeBase("kb-1"))
			.thenReturn(knowledgeBase());
		when(this.knowledgeBaseService.requireKnowledgeBaseForUpdate("kb-1"))
			.thenReturn(knowledgeBase());
	}

	/**
	 * 清理测试租户上下文。
	 */
	@AfterEach
	public void tearDown() {
		TenantContext.clear();
	}

	/**
	 * 验证新来源会登记 processing 版本一。
	 */
	@Test
	public void shouldRegisterNewDocumentVersion() {
		when(this.knowledgeDocumentMapper.selectOne(any())).thenReturn(null);
		when(this.knowledgeDocumentMapper.selectVersionsForUpdate(eq("ENT001"), eq("kb-1"), any()))
			.thenReturn(List.of());
		ArgumentCaptor<KnowledgeDocumentEntity> captor = ArgumentCaptor.forClass(KnowledgeDocumentEntity.class);

		DocumentImportRegistration result = this.service.beginImport(
			"kb-1", null, "manual.pdf", "application/pdf", 128L);

		verify(this.knowledgeDocumentMapper).insert(captor.capture());
		assertThat(result.version()).isEqualTo(1);
		assertThat(captor.getValue().getStatus()).isEqualTo("processing");
		assertThat(captor.getValue().getEntCode()).isEqualTo("ENT001");
	}

	/**
	 * 验证显式替换不存在文档时按业务错误拒绝。
	 */
	@Test
	public void shouldRejectReplacingMissingDocument() {
		when(this.knowledgeDocumentMapper.selectVersionsForUpdate("ENT001", "kb-1", "missing"))
			.thenReturn(List.of());

		assertThatThrownBy(() -> this.service.beginImport(
			"kb-1", "missing", "manual.pdf", "application/pdf", 128L))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("知识文档不存在");
	}

	/**
	 * 验证新版本晋级时上一 ready 版本转为 superseded。
	 */
	@Test
	public void shouldPromoteNewVersionAndSupersedePreviousReadyVersion() {
		KnowledgeDocumentEntity previous = documentVersion(1, "ready");
		KnowledgeDocumentEntity current = documentVersion(2, "processing");
		when(this.knowledgeDocumentMapper.selectVersionsForUpdate("ENT001", "kb-1", "doc-1"))
			.thenReturn(List.of(previous, current));

		DocumentPromotionResult result = this.service.markReady(
			new DocumentImportRegistration("doc-1", "kb-1", "manual.pdf", 2), 6, "abc");

		assertThat(result.supersededVersions()).containsExactly(1);
		assertThat(result.status()).isEqualTo("ready");
		assertThat(previous.getStatus()).isEqualTo("superseded");
		assertThat(current.getStatus()).isEqualTo("ready");
		assertThat(current.getChunkCount()).isEqualTo(6);
		assertThat(current.getChecksumSha256()).isEqualTo("abc");
		assertThat(current.getEmbeddingModel()).isEqualTo(EmbeddingModelMetadata.CURRENT_MODEL_ID);
		verify(this.knowledgeDocumentMapper).updateById(previous);
		verify(this.knowledgeDocumentMapper).updateById(current);
	}

	/**
	 * 验证失败版本不会修改上一可用版本。
	 */
	@Test
	public void shouldMarkOnlyProcessingVersionAsFailed() {
		KnowledgeDocumentEntity previous = documentVersion(1, "ready");
		KnowledgeDocumentEntity current = documentVersion(2, "processing");
		when(this.knowledgeDocumentMapper.selectVersionsForUpdate("ENT001", "kb-1", "doc-1"))
			.thenReturn(List.of(previous, current));

		this.service.markFailed(
			new DocumentImportRegistration("doc-1", "kb-1", "manual.pdf", 2),
			new IllegalStateException("模拟失败"));

		assertThat(previous.getStatus()).isEqualTo("ready");
		assertThat(current.getStatus()).isEqualTo("failed");
		assertThat(current.getErrorMessage()).isEqualTo("文档解析或向量写入失败，请稍后重试");
	}

	/**
	 * 验证删除会软删除稳定文档的全部版本。
	 */
	@Test
	public void shouldSoftDeleteAllDocumentVersions() {
		KnowledgeDocumentEntity previous = documentVersion(1, "superseded");
		KnowledgeDocumentEntity current = documentVersion(2, "ready");
		when(this.knowledgeDocumentMapper.selectVersionsForUpdate("ENT001", "kb-1", "doc-1"))
			.thenReturn(List.of(previous, current));

		List<Integer> versions = this.service.deleteDocument("kb-1", "doc-1");

		assertThat(versions).containsExactly(1, 2);
		assertThat(previous.getStatus()).isEqualTo("deleted");
		assertThat(current.getStatus()).isEqualTo("deleted");
	}

	/**
	 * 构建测试知识库。
	 *
	 * @return 知识库实体
	 */
	private KnowledgeBaseEntity knowledgeBase() {
		KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
		entity.setId(1L);
		entity.setKnowledgeBaseId("kb-1");
		entity.setEntCode("ENT001");
		entity.setStatus("active");
		return entity;
	}

	/**
	 * 构建测试文档版本。
	 *
	 * @param version 版本号
	 * @param status  状态
	 * @return 文档版本实体
	 */
	private KnowledgeDocumentEntity documentVersion(int version, String status) {
		KnowledgeDocumentEntity entity = new KnowledgeDocumentEntity();
		entity.setId((long) version);
		entity.setDocumentId("doc-1");
		entity.setKnowledgeBaseId("kb-1");
		entity.setEntCode("ENT001");
		entity.setSourceName("manual.pdf");
		entity.setVersion(version);
		entity.setStatus(status);
		return entity;
	}

	/**
	 * 验证较旧版本迟到完成时不会覆盖已经 ready 的较新版本。
	 */
	@Test
	public void shouldKeepNewerReadyVersionWhenOlderImportFinishesLater() {
		KnowledgeDocumentEntity older = documentVersion(2, "processing");
		KnowledgeDocumentEntity newer = documentVersion(3, "ready");
		when(this.knowledgeDocumentMapper.selectVersionsForUpdate("ENT001", "kb-1", "doc-1"))
			.thenReturn(List.of(older, newer));

		DocumentPromotionResult result = this.service.markReady(
			new DocumentImportRegistration("doc-1", "kb-1", "manual.pdf", 2), 6, "abc");

		assertThat(result.status()).isEqualTo("superseded");
		assertThat(result.supersededVersions()).containsExactly(2);
		assertThat(older.getStatus()).isEqualTo("superseded");
		assertThat(older.getChunkCount()).isZero();
		assertThat(newer.getStatus()).isEqualTo("ready");
		verify(this.knowledgeDocumentMapper).updateById(older);
	}

	/**
	 * 验证显式替换不能占用其他稳定文档已使用的来源名。
	 */
	@Test
	public void shouldRejectReplacingWithAnotherDocumentSource() {
		when(this.knowledgeDocumentMapper.selectVersionsForUpdate("ENT001", "kb-1", "doc-1"))
			.thenReturn(List.of(documentVersion(1, "ready")));
		when(this.knowledgeDocumentMapper.selectCount(any())).thenReturn(1L);

		assertThatThrownBy(() -> this.service.beginImport(
			"kb-1", "doc-1", "other.pdf", "application/pdf", 128L))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("同名来源已属于其他知识文档");
		verify(this.knowledgeDocumentMapper, never()).insert(any(KnowledgeDocumentEntity.class));
	}

	/**
	 * 验证标题匹配所需的 ready 文档身份只使用当前租户和知识库数据。
	 */
	@Test
	public void shouldReturnReadyDocumentMetadataForCurrentKnowledgeBase() {
		KnowledgeDocumentEntity ready = documentVersion(3, "ready");
		ready.setSourceName("渠道技术方案.docx");
		when(this.knowledgeDocumentMapper.selectList(any())).thenReturn(List.of(ready));

		List<ManagedDocumentMetadata> result = this.service.findReadyDocuments("kb-1");

		assertThat(result).containsExactly(new ManagedDocumentMetadata(
			"ENT001", "kb-1", "doc-1", 3, "渠道技术方案.docx"));
	}

	/**
	 * 验证旧模型的可用文档会明确提示重新导入。
	 */
	@Test
	public void shouldMarkOldModelReadyDocumentAsReindexRequired() {
		KnowledgeDocumentEntity ready = documentVersion(1, "ready");
		ready.setChunkCount(3);
		when(this.knowledgeDocumentMapper.selectList(any())).thenReturn(List.of(ready));

		assertThat(this.service.listDocuments("kb-1"))
			.singleElement()
			.satisfies(item -> assertThat(item.requiresReindex()).isTrue());

		ready.setEmbeddingModel(EmbeddingModelMetadata.CURRENT_MODEL_ID);
		assertThat(this.service.listDocuments("kb-1"))
			.singleElement()
			.satisfies(item -> assertThat(item.requiresReindex()).isFalse());
	}

	/**
	 * 验证旧模型版本不会被当作当前可引用来源。
	 */
	@Test
	public void shouldExcludeOldModelVersionFromReadySources() {
		KnowledgeDocumentEntity ready = documentVersion(1, "ready");
		DocumentVersionKey requested = new DocumentVersionKey("doc-1", 1);
		when(this.knowledgeDocumentMapper.selectList(any())).thenReturn(List.of(ready));

		assertThat(this.service.findReadyVersionSources("kb-1", Set.of(requested))).isEmpty();

		ready.setEmbeddingModel(EmbeddingModelMetadata.CURRENT_MODEL_ID);
		assertThat(this.service.findReadyVersionSources("kb-1", Set.of(requested)))
			.containsEntry(requested, "manual.pdf");
	}

}
