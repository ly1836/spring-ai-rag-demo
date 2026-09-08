package com.example.rag.knowledge;

import java.io.ByteArrayInputStream;
import java.util.List;

import com.example.rag.chat.DocumentLoaderService;
import com.example.rag.config.TenantContext;
import com.example.rag.knowledge.dto.DocumentImportRegistration;
import com.example.rag.knowledge.dto.DocumentPromotionResult;
import com.example.rag.knowledge.dto.ManagedDocumentLoadResult;
import com.example.rag.knowledge.dto.ManagedDocumentMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 知识文档导入编排服务测试。
 */
class KnowledgeDocumentIngestionServiceTest {

	private KnowledgeDocumentService knowledgeDocumentService;

	private DocumentLoaderService documentLoaderService;

	private KnowledgeDocumentIngestionService service;

	/**
	 * 初始化测试依赖和租户上下文。
	 */
	@BeforeEach
	public void setUp() {
		this.knowledgeDocumentService = mock(KnowledgeDocumentService.class);
		this.documentLoaderService = mock(DocumentLoaderService.class);
		this.service = new KnowledgeDocumentIngestionService(
			this.knowledgeDocumentService, this.documentLoaderService);
		TenantContext.setEntCode("ENT001");
	}

	/**
	 * 清理测试租户上下文。
	 */
	@AfterEach
	public void tearDown() {
		TenantContext.clear();
	}

	/**
	 * 验证登记、向量写入和状态晋级按顺序执行。
	 */
	@Test
	public void shouldCompleteThreeStageImport() {
		DocumentImportRegistration registration =
			new DocumentImportRegistration("doc-1", "kb-1", "manual.txt", 2);
		when(this.knowledgeDocumentService.beginImport(
			"kb-1", "doc-1", "manual.txt", "text/plain", 4L)).thenReturn(registration);
		when(this.documentLoaderService.loadManagedFile(any(), any(ManagedDocumentMetadata.class)))
			.thenReturn(new ManagedDocumentLoadResult(3, "abc"));
		when(this.knowledgeDocumentService.markReady(registration, 3, "abc"))
			.thenReturn(new DocumentPromotionResult(List.of(1), 3, "ready"));

		var result = this.service.importFile("kb-1", "doc-1",
			new ByteArrayInputStream("test".getBytes()), "manual.txt", "text/plain", 4L);

		assertThat(result.status()).isEqualTo("ready");
		assertThat(result.chunksLoaded()).isEqualTo(3);
		var ordered = inOrder(this.knowledgeDocumentService, this.documentLoaderService);
		ordered.verify(this.knowledgeDocumentService).beginImport(
			"kb-1", "doc-1", "manual.txt", "text/plain", 4L);
		ordered.verify(this.documentLoaderService).loadManagedFile(any(), any(ManagedDocumentMetadata.class));
		ordered.verify(this.knowledgeDocumentService).markReady(registration, 3, "abc");
	}

	/**
	 * 验证向量写入失败会清理当前版本、标记失败并转换为系统异常。
	 */
	@Test
	public void shouldCompensateFailedImportWithoutPromotingVersion() {
		DocumentImportRegistration registration =
			new DocumentImportRegistration("doc-1", "kb-1", "manual.txt", 2);
		when(this.knowledgeDocumentService.beginImport(
			"kb-1", "doc-1", "manual.txt", "text/plain", 4L)).thenReturn(registration);
		when(this.documentLoaderService.loadManagedFile(any(), any(ManagedDocumentMetadata.class)))
			.thenThrow(new IllegalStateException("模拟向量写入失败"));

		assertThatThrownBy(() -> this.service.importFile("kb-1", "doc-1",
			new ByteArrayInputStream("test".getBytes()), "manual.txt", "text/plain", 4L))
			.isInstanceOf(KnowledgeInfrastructureException.class)
			.hasMessage("知识文档导入失败，请稍后重试")
			.hasCauseInstanceOf(IllegalStateException.class);

		verify(this.documentLoaderService).deleteManagedVersion(any(ManagedDocumentMetadata.class));
		verify(this.knowledgeDocumentService).markFailed(any(DocumentImportRegistration.class), any(Throwable.class));
	}

	/**
	 * 验证解析后的资源边界错误仍保持 PARAM_ERROR 语义。
	 */
	@Test
	public void shouldKeepManagedLoadBoundaryFailureAsParameterError() {
		DocumentImportRegistration registration =
			new DocumentImportRegistration("doc-1", "kb-1", "manual.txt", 2);
		when(this.knowledgeDocumentService.beginImport(
			"kb-1", "doc-1", "manual.txt", "text/plain", 4L)).thenReturn(registration);
		when(this.documentLoaderService.loadManagedFile(any(), any(ManagedDocumentMetadata.class)))
			.thenThrow(new IllegalArgumentException("文档分片数量超过限制"));

		assertThatThrownBy(() -> this.service.importFile("kb-1", "doc-1",
			new ByteArrayInputStream("test".getBytes()), "manual.txt", "text/plain", 4L))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("文档分片数量超过限制");

		verify(this.knowledgeDocumentService).markFailed(
			any(DocumentImportRegistration.class), any(IllegalArgumentException.class));
	}

	/**
	 * 验证版本晋级时的业务状态冲突仍保持 BIZ_ERROR 语义。
	 */
	@Test
	public void shouldKeepPromotionStateFailureAsBusinessError() {
		DocumentImportRegistration registration =
			new DocumentImportRegistration("doc-1", "kb-1", "manual.txt", 2);
		when(this.knowledgeDocumentService.beginImport(
			"kb-1", "doc-1", "manual.txt", "text/plain", 4L)).thenReturn(registration);
		when(this.documentLoaderService.loadManagedFile(any(), any(ManagedDocumentMetadata.class)))
			.thenReturn(new ManagedDocumentLoadResult(3, "abc"));
		when(this.knowledgeDocumentService.markReady(registration, 3, "abc"))
			.thenThrow(new IllegalStateException("文档版本状态已变化"));

		assertThatThrownBy(() -> this.service.importFile("kb-1", "doc-1",
			new ByteArrayInputStream("test".getBytes()), "manual.txt", "text/plain", 4L))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("文档版本状态已变化");

		verify(this.knowledgeDocumentService).markFailed(
			any(DocumentImportRegistration.class), any(IllegalStateException.class));
	}

	/**
	 * 验证迟到完成的旧版本会返回 superseded 并清理自身向量。
	 */
	@Test
	public void shouldCleanupLateSupersededVersion() {
		DocumentImportRegistration registration =
			new DocumentImportRegistration("doc-1", "kb-1", "manual.txt", 2);
		when(this.knowledgeDocumentService.beginImport(
			"kb-1", "doc-1", "manual.txt", "text/plain", 4L)).thenReturn(registration);
		when(this.documentLoaderService.loadManagedFile(any(), any(ManagedDocumentMetadata.class)))
			.thenReturn(new ManagedDocumentLoadResult(3, "abc"));
		when(this.knowledgeDocumentService.markReady(registration, 3, "abc"))
			.thenReturn(new DocumentPromotionResult(List.of(2), 0, "superseded"));

		var result = this.service.importFile("kb-1", "doc-1",
			new ByteArrayInputStream("test".getBytes()), "manual.txt", "text/plain", 4L);

		assertThat(result.status()).isEqualTo("superseded");
		assertThat(result.chunksLoaded()).isZero();
		verify(this.documentLoaderService).deleteManagedVersion(
			new ManagedDocumentMetadata("ENT001", "kb-1", "doc-1", 2, "manual.txt"));
	}

	/**
	 * 验证不支持的文件格式会在登记 processing 版本前被拒绝。
	 */
	@Test
	public void shouldRejectUnsupportedFileFormatBeforeRegistration() {
		assertThatThrownBy(() -> this.service.importFile("kb-1", null,
			new ByteArrayInputStream("test".getBytes()), "script.exe", "application/octet-stream", 4L))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("不支持的文档格式");

		verifyNoInteractions(this.knowledgeDocumentService, this.documentLoaderService);
	}

}
