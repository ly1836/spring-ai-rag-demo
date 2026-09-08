package com.example.rag.knowledge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.example.rag.chat.DocumentLoaderService;
import com.example.rag.config.TenantContext;
import com.example.rag.knowledge.dto.DocumentImportRegistration;
import com.example.rag.knowledge.dto.DocumentPromotionResult;
import com.example.rag.knowledge.dto.ManagedDocumentLoadResult;
import com.example.rag.knowledge.dto.ManagedDocumentMetadata;
import com.example.rag.vo.KnowledgeVO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

/**
 * 知识文档导入编排服务。
 */
@Service
public class KnowledgeDocumentIngestionService {

	private static final Logger log = LoggerFactory.getLogger(KnowledgeDocumentIngestionService.class);

	/** 管理端和兼容入口允许导入的文档扩展名。 */
	private static final Set<String> SUPPORTED_FILE_EXTENSIONS = Set.of(
		"pdf", "doc", "docx", "xls", "xlsx", "txt", "html", "rtf");

	private final KnowledgeDocumentService knowledgeDocumentService;

	private final DocumentLoaderService documentLoaderService;

	/**
	 * 创建知识文档导入编排服务。
	 *
	 * @param knowledgeDocumentService 文档注册服务
	 * @param documentLoaderService    文档提取与向量写入服务
	 */
	public KnowledgeDocumentIngestionService(KnowledgeDocumentService knowledgeDocumentService,
			DocumentLoaderService documentLoaderService) {
		this.knowledgeDocumentService = knowledgeDocumentService;
		this.documentLoaderService = documentLoaderService;
	}

	/**
	 * 导入或替换受管文件。
	 *
	 * @param knowledgeBaseId 知识库 ID
	 * @param documentId      可空稳定文档 ID
	 * @param inputStream     文件输入流
	 * @param sourceName      来源名称
	 * @param contentType     内容类型
	 * @param sizeBytes       文件大小
	 * @return 导入结果
	 * @throws IllegalArgumentException 文件为空或内容越界时抛出 PARAM_ERROR
	 * @throws IllegalStateException 知识库或文档状态非法时抛出 BIZ_ERROR
	 * @throws KnowledgeInfrastructureException 文档解析、嵌入或向量基础设施异常时抛出 SYSTEM_ERROR
	 */
	public KnowledgeVO.KnowledgeDocumentImportResponse importFile(String knowledgeBaseId,
			String documentId, InputStream inputStream, String sourceName,
			String contentType, long sizeBytes) {
		if (inputStream == null || sizeBytes == 0) {
			throw new IllegalArgumentException("上传文件不能为空");
		}
		// 在登记 processing 版本前完成格式校验，避免非法文件产生导入记录。
		validateSupportedFileFormat(sourceName);
		DocumentImportRegistration registration = this.knowledgeDocumentService.beginImport(
			knowledgeBaseId, documentId, sourceName, contentType, sizeBytes);
		ManagedDocumentMetadata metadata = toMetadata(registration);
		ManagedDocumentLoadResult loadResult;
		try {
			loadResult = this.documentLoaderService.loadManagedFile(inputStream, metadata);
		}
		catch (RuntimeException ex) {
			throw completeFailedImport(registration, metadata, ex, true);
		}
		try {
			DocumentPromotionResult promotion = this.knowledgeDocumentService.markReady(
				registration, loadResult.chunkCount(), loadResult.checksumSha256());
			cleanupSupersededVersions(metadata, promotion.supersededVersions());
			return toImportResponse(registration, promotion);
		}
		catch (RuntimeException ex) {
			throw completeFailedImport(registration, metadata, ex, false);
		}
	}

	/**
	 * 导入评测或测试使用的受管纯文本。
	 *
	 * @param knowledgeBaseId 知识库 ID
	 * @param documentId      可空稳定文档 ID
	 * @param sourceName      来源名称
	 * @param text            文本内容
	 * @return 导入结果
	 * @throws IllegalArgumentException 文本为空或内容越界时抛出 PARAM_ERROR
	 * @throws IllegalStateException 知识库或文档状态非法时抛出 BIZ_ERROR
	 * @throws KnowledgeInfrastructureException 嵌入或向量基础设施异常时抛出 SYSTEM_ERROR
	 */
	public KnowledgeVO.KnowledgeDocumentImportResponse importText(String knowledgeBaseId,
			String documentId, String sourceName, String text) {
		if (text == null || text.isBlank()) {
			throw new IllegalArgumentException("导入文本不能为空");
		}
		long sizeBytes = text.getBytes(StandardCharsets.UTF_8).length;
		DocumentImportRegistration registration = this.knowledgeDocumentService.beginImport(
			knowledgeBaseId, documentId, sourceName, "text/plain", sizeBytes);
		ManagedDocumentMetadata metadata = toMetadata(registration);
		ManagedDocumentLoadResult loadResult;
		try {
			loadResult = this.documentLoaderService.loadManagedText(text, metadata);
		}
		catch (RuntimeException ex) {
			throw completeFailedImport(registration, metadata, ex, true);
		}
		try {
			DocumentPromotionResult promotion = this.knowledgeDocumentService.markReady(
				registration, loadResult.chunkCount(), loadResult.checksumSha256());
			cleanupSupersededVersions(metadata, promotion.supersededVersions());
			return toImportResponse(registration, promotion);
		}
		catch (RuntimeException ex) {
			throw completeFailedImport(registration, metadata, ex, false);
		}
	}

	/**
	 * 将 classpath:docs 下预置文件导入默认知识库。
	 *
	 * @return 总分片数量
	 * @throws KnowledgeInfrastructureException 读取预置资源失败时抛出 SYSTEM_ERROR
	 */
	public int importClasspathDocuments() {
		try {
			Resource[] resources = new PathMatchingResourcePatternResolver()
				.getResources("classpath:docs/*.*");
			int total = 0;
			for (Resource resource : resources) {
				String filename = resource.getFilename();
				if (filename == null) {
					continue;
				}
				try (InputStream inputStream = resource.getInputStream()) {
					total += importFile(null, null, inputStream, filename,
						"application/octet-stream", resource.contentLength()).chunksLoaded();
				}
			}
			return total;
		}
		catch (IOException ex) {
			throw new KnowledgeInfrastructureException("知识文档导入失败，请稍后重试", ex);
		}
	}

	/**
	 * 删除稳定文档并清理全部版本向量。
	 *
	 * @param knowledgeBaseId 知识库 ID
	 * @param documentId      稳定文档 ID
	 * @return 删除响应
	 * @throws IllegalStateException 文档不存在时抛出 BIZ_ERROR
	 */
	public KnowledgeVO.DeleteKnowledgeDocumentResponse deleteDocument(String knowledgeBaseId,
			String documentId) {
		List<Integer> versions = this.knowledgeDocumentService.deleteDocument(knowledgeBaseId, documentId);
		String entCode = TenantContext.requireEntCode();
		for (Integer version : versions) {
			try {
				this.documentLoaderService.deleteManagedVersion(new ManagedDocumentMetadata(
					entCode, knowledgeBaseId, documentId, version, documentId));
			}
			catch (RuntimeException ex) {
				log.warn("删除知识文档后清理向量失败: documentId={}, version={}, error={}",
					documentId, version, ex.getMessage());
			}
		}
		return new KnowledgeVO.DeleteKnowledgeDocumentResponse(documentId);
	}

	/**
	 * 将导入登记转换为向量元数据。
	 *
	 * @param registration 导入登记
	 * @return 受管向量元数据
	 */
	private ManagedDocumentMetadata toMetadata(DocumentImportRegistration registration) {
		return new ManagedDocumentMetadata(TenantContext.requireEntCode(), registration.knowledgeBaseId(),
			registration.documentId(), registration.version(), registration.sourceName());
	}

	/**
	 * 清理已经失效的上一可用版本向量。
	 *
	 * @param currentMetadata   当前版本元数据
	 * @param supersededVersions 已失效版本号
	 */
	private void cleanupSupersededVersions(ManagedDocumentMetadata currentMetadata,
			List<Integer> supersededVersions) {
		for (Integer version : supersededVersions) {
			try {
				this.documentLoaderService.deleteManagedVersion(new ManagedDocumentMetadata(
					currentMetadata.entCode(), currentMetadata.knowledgeBaseId(),
					currentMetadata.documentId(), version, currentMetadata.sourceName()));
			}
			catch (RuntimeException ex) {
				log.warn("清理已失效文档版本向量失败: documentId={}, version={}, error={}",
					currentMetadata.documentId(), version, ex.getMessage());
			}
		}
	}

	/**
	 * 尽力清理失败版本向量。
	 *
	 * @param metadata 失败版本元数据
	 */
	private void cleanupFailedVersion(ManagedDocumentMetadata metadata) {
		try {
			this.documentLoaderService.deleteManagedVersion(metadata);
		}
		catch (RuntimeException cleanupEx) {
			log.warn("清理失败文档版本向量失败: documentId={}, version={}, error={}",
				metadata.documentId(), metadata.documentVersion(), cleanupEx.getMessage());
		}
	}

	/**
	 * 构建成功导入响应。
	 *
	 * @param registration 导入登记
	 * @param promotion     版本晋级结果
	 * @return 导入响应
	 */
	private KnowledgeVO.KnowledgeDocumentImportResponse toImportResponse(
			DocumentImportRegistration registration, DocumentPromotionResult promotion) {
		return new KnowledgeVO.KnowledgeDocumentImportResponse(registration.documentId(),
			registration.knowledgeBaseId(), registration.sourceName(), registration.version(),
			promotion.status(), promotion.chunkCount());
	}

	/**
	 * 校验上传文件扩展名是否属于当前支持的文档格式。
	 *
	 * @param sourceName 上传文件名称
	 * @throws IllegalArgumentException 文件名称缺少扩展名或格式不支持时抛出
	 */
	private void validateSupportedFileFormat(String sourceName) {
		String normalizedSourceName = sourceName == null ? "" : sourceName.trim();
		int separatorIndex = normalizedSourceName.lastIndexOf('.');
		if (separatorIndex < 0 || separatorIndex == normalizedSourceName.length() - 1) {
			throw new IllegalArgumentException("不支持的文档格式，仅支持 PDF、Word、Excel、TXT、HTML 和 RTF");
		}
		String extension = normalizedSourceName.substring(separatorIndex + 1).toLowerCase(Locale.ROOT);
		if (!SUPPORTED_FILE_EXTENSIONS.contains(extension)) {
			throw new IllegalArgumentException("不支持的文档格式，仅支持 PDF、Word、Excel、TXT、HTML 和 RTF");
		}
	}

	/**
	 * 补偿失败版本并保留参数、业务与系统异常的既定错误语义。
	 *
	 * @param registration        导入登记
	 * @param metadata            失败版本向量元数据
	 * @param failure             原始异常
	 * @param infrastructureStage 是否发生在解析、嵌入或向量写入阶段
	 * @return 应继续抛出的异常
	 */
	private RuntimeException completeFailedImport(DocumentImportRegistration registration,
			ManagedDocumentMetadata metadata, RuntimeException failure,
			boolean infrastructureStage) {
		cleanupFailedVersion(metadata);
		try {
			this.knowledgeDocumentService.markFailed(registration, failure);
		}
		catch (RuntimeException markFailedError) {
			markFailedError.addSuppressed(failure);
			return new KnowledgeInfrastructureException("知识文档导入失败，请稍后重试", markFailedError);
		}
		if (infrastructureStage && !(failure instanceof IllegalArgumentException)) {
			return new KnowledgeInfrastructureException("知识文档导入失败，请稍后重试", failure);
		}
		return failure;
	}

}
