package com.example.rag.knowledge;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.rag.chat.rag.EmbeddingModelMetadata;
import com.example.rag.config.TenantContext;
import com.example.rag.dao.entity.KnowledgeBaseEntity;
import com.example.rag.dao.entity.KnowledgeDocumentEntity;
import com.example.rag.dao.mapper.KnowledgeDocumentMapper;
import com.example.rag.knowledge.dto.DocumentImportRegistration;
import com.example.rag.knowledge.dto.DocumentPromotionResult;
import com.example.rag.knowledge.dto.DocumentVersionKey;
import com.example.rag.knowledge.dto.ManagedDocumentMetadata;
import com.example.rag.vo.KnowledgeVO;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识文档注册和版本状态服务。
 */
@Service
public class KnowledgeDocumentService {

	private static final DateTimeFormatter DATE_TIME_FORMATTER =
		DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private static final int MAX_SOURCE_NAME_LENGTH = 255;

	private final KnowledgeBaseService knowledgeBaseService;

	private final KnowledgeDocumentMapper knowledgeDocumentMapper;

	/**
	 * 创建知识文档注册服务。
	 *
	 * @param knowledgeBaseService     知识库服务
	 * @param knowledgeDocumentMapper  文档版本 Mapper
	 */
	public KnowledgeDocumentService(KnowledgeBaseService knowledgeBaseService,
			KnowledgeDocumentMapper knowledgeDocumentMapper) {
		this.knowledgeBaseService = knowledgeBaseService;
		this.knowledgeDocumentMapper = knowledgeDocumentMapper;
	}

	/**
	 * 登记文档导入版本。
	 *
	 * @param knowledgeBaseId 知识库 ID
	 * @param documentId      可空稳定文档 ID，空值时按同名来源复用或新建
	 * @param sourceName      来源名称
	 * @param contentType     内容类型
	 * @param sizeBytes       文件大小
	 * @return 导入登记结果
	 * @throws IllegalArgumentException 来源名称或文件大小非法时抛出 PARAM_ERROR
	 * @throws IllegalStateException 知识库或文档不存在、知识库已停用时抛出 BIZ_ERROR
	 */
	@Transactional(transactionManager = "erpTransactionManager")
	public DocumentImportRegistration beginImport(String knowledgeBaseId, String documentId,
			String sourceName, String contentType, long sizeBytes) {
		String entCode = TenantContext.requireEntCode();
		KnowledgeBaseEntity knowledgeBase =
			this.knowledgeBaseService.resolveActiveKnowledgeBaseForUpdate(knowledgeBaseId);
		String normalizedSource = validateSourceName(sourceName);
		if (sizeBytes < 0) {
			throw new IllegalArgumentException("文件大小不能为负数");
		}
		String stableDocumentId = resolveDocumentId(
			entCode, knowledgeBase.getKnowledgeBaseId(), documentId, normalizedSource);
		List<KnowledgeDocumentEntity> versions = this.knowledgeDocumentMapper.selectVersionsForUpdate(
			entCode, knowledgeBase.getKnowledgeBaseId(), stableDocumentId);
		int nextVersion = versions.stream()
			.map(KnowledgeDocumentEntity::getVersion)
			.filter(value -> value != null)
			.max(Integer::compareTo)
			.orElse(0) + 1;
		KnowledgeDocumentEntity entity = new KnowledgeDocumentEntity();
		entity.setDocumentId(stableDocumentId);
		entity.setKnowledgeBaseId(knowledgeBase.getKnowledgeBaseId());
		entity.setEntCode(entCode);
		entity.setSourceName(normalizedSource);
		entity.setContentType(normalizeContentType(contentType));
		entity.setSizeBytes(sizeBytes);
		entity.setVersion(nextVersion);
		entity.setStatus("processing");
		entity.setChunkCount(0);
		entity.setCreatedBy(TenantContext.getUserIdOrDefault());
		this.knowledgeDocumentMapper.insert(entity);
		return new DocumentImportRegistration(stableDocumentId, knowledgeBase.getKnowledgeBaseId(),
			normalizedSource, nextVersion);
	}

	/**
	 * 将完成向量写入的新版本晋级为可用版本。
	 *
	 * @param registration 导入登记结果
	 * @param chunkCount    实际分片数
	 * @param checksumSha256 文件 SHA-256 摘要
	 * @return 被替换的上一可用版本
	 * @throws IllegalStateException processing 版本不存在时抛出 BIZ_ERROR
	 */
	@Transactional(transactionManager = "erpTransactionManager")
	public DocumentPromotionResult markReady(DocumentImportRegistration registration,
			int chunkCount, String checksumSha256) {
		String entCode = TenantContext.requireEntCode();
		List<KnowledgeDocumentEntity> versions = this.knowledgeDocumentMapper.selectVersionsForUpdate(
			entCode, registration.knowledgeBaseId(), registration.documentId());
		KnowledgeDocumentEntity target = findVersion(versions, registration.version());
		if (!"processing".equals(target.getStatus())) {
			throw new IllegalStateException("文档版本状态不是 processing，不能晋级");
		}
		boolean newerReadyExists = versions.stream()
			.anyMatch(version -> "ready".equals(version.getStatus())
				&& version.getVersion() != null
				&& version.getVersion() > registration.version());
		if (newerReadyExists) {
			// 较新版本已先完成时，当前迟到版本不得覆盖最新可用版本。
			target.setStatus("superseded");
			target.setChunkCount(0);
			target.setChecksumSha256(checksumSha256);
			target.setEmbeddingModel(EmbeddingModelMetadata.CURRENT_MODEL_ID);
			target.setErrorMessage(null);
			this.knowledgeDocumentMapper.updateById(target);
			return new DocumentPromotionResult(List.of(registration.version()), 0, "superseded");
		}
		List<Integer> supersededVersions = new ArrayList<>();
		for (KnowledgeDocumentEntity version : versions) {
			if ("ready".equals(version.getStatus())
					&& version.getVersion() != null
					&& version.getVersion() < registration.version()) {
				supersededVersions.add(version.getVersion());
				version.setStatus("superseded");
				this.knowledgeDocumentMapper.updateById(version);
			}
		}
		target.setStatus("ready");
		target.setChunkCount(chunkCount);
		target.setChecksumSha256(checksumSha256);
		target.setEmbeddingModel(EmbeddingModelMetadata.CURRENT_MODEL_ID);
		target.setErrorMessage(null);
		this.knowledgeDocumentMapper.updateById(target);
		return new DocumentPromotionResult(List.copyOf(supersededVersions), chunkCount, "ready");
	}

	/**
	 * 将未完成导入的版本标记为失败。
	 *
	 * @param registration 导入登记结果
	 * @param error        导入异常
	 */
	@Transactional(transactionManager = "erpTransactionManager")
	public void markFailed(DocumentImportRegistration registration, Throwable error) {
		String entCode = TenantContext.requireEntCode();
		List<KnowledgeDocumentEntity> versions = this.knowledgeDocumentMapper.selectVersionsForUpdate(
			entCode, registration.knowledgeBaseId(), registration.documentId());
		KnowledgeDocumentEntity target = findVersion(versions, registration.version());
		if ("processing".equals(target.getStatus())) {
			target.setStatus("failed");
			target.setChunkCount(0);
			target.setErrorMessage(safeErrorMessage(error));
			this.knowledgeDocumentMapper.updateById(target);
		}
	}

	/**
	 * 查询知识库中的稳定文档列表。
	 *
	 * @param knowledgeBaseId 知识库 ID
	 * @return 按最近更新时间倒序排列的文档列表
	 * @throws IllegalStateException 知识库不存在时抛出 BIZ_ERROR
	 */
	public List<KnowledgeVO.KnowledgeDocumentItem> listDocuments(String knowledgeBaseId) {
		KnowledgeBaseEntity knowledgeBase = this.knowledgeBaseService.requireKnowledgeBase(knowledgeBaseId);
		String entCode = TenantContext.requireEntCode();
		List<KnowledgeDocumentEntity> rows = this.knowledgeDocumentMapper.selectList(
			new LambdaQueryWrapper<KnowledgeDocumentEntity>()
				.eq(KnowledgeDocumentEntity::getEntCode, entCode)
				.eq(KnowledgeDocumentEntity::getKnowledgeBaseId, knowledgeBase.getKnowledgeBaseId())
				.ne(KnowledgeDocumentEntity::getStatus, "deleted")
				.orderByAsc(KnowledgeDocumentEntity::getDocumentId)
				.orderByAsc(KnowledgeDocumentEntity::getVersion));
		Map<String, List<KnowledgeDocumentEntity>> grouped = new LinkedHashMap<>();
		for (KnowledgeDocumentEntity row : rows) {
			grouped.computeIfAbsent(row.getDocumentId(), key -> new ArrayList<>()).add(row);
		}
		return grouped.values().stream()
			.map(this::toDocumentItem)
			.sorted(Comparator.comparing(KnowledgeVO.KnowledgeDocumentItem::updatedAt).reversed())
			.toList();
	}

	/**
	 * 软删除稳定文档的全部版本。
	 *
	 * @param knowledgeBaseId 知识库 ID
	 * @param documentId      稳定文档 ID
	 * @return 删除前的全部版本号
	 * @throws IllegalStateException 文档不存在时抛出 BIZ_ERROR
	 */
	@Transactional(transactionManager = "erpTransactionManager")
	public List<Integer> deleteDocument(String knowledgeBaseId, String documentId) {
		KnowledgeBaseEntity knowledgeBase =
			this.knowledgeBaseService.requireKnowledgeBaseForUpdate(knowledgeBaseId);
		String entCode = TenantContext.requireEntCode();
		List<KnowledgeDocumentEntity> versions = this.knowledgeDocumentMapper.selectVersionsForUpdate(
			entCode, knowledgeBase.getKnowledgeBaseId(), documentId);
		List<KnowledgeDocumentEntity> activeVersions = versions.stream()
			.filter(version -> !"deleted".equals(version.getStatus()))
			.toList();
		if (activeVersions.isEmpty()) {
			throw new IllegalStateException("知识文档不存在");
		}
		for (KnowledgeDocumentEntity version : activeVersions) {
			version.setStatus("deleted");
			this.knowledgeDocumentMapper.updateById(version);
		}
		return activeVersions.stream().map(KnowledgeDocumentEntity::getVersion).toList();
	}

	/**
	 * 根据显式文档或同名来源解析稳定文档 ID。
	 *
	 * @param entCode        租户编码
	 * @param knowledgeBaseId 知识库 ID
	 * @param documentId     可空显式文档 ID
	 * @param sourceName     来源名称
	 * @return 稳定文档 ID
	 * @throws IllegalStateException 显式文档不存在时抛出 BIZ_ERROR
	 */
	private String resolveDocumentId(String entCode, String knowledgeBaseId,
			String documentId, String sourceName) {
		if (documentId != null && !documentId.isBlank()) {
			List<KnowledgeDocumentEntity> versions = this.knowledgeDocumentMapper.selectVersionsForUpdate(
				entCode, knowledgeBaseId, documentId);
			boolean exists = versions.stream().anyMatch(version -> !"deleted".equals(version.getStatus()));
			if (!exists) {
				throw new IllegalStateException("知识文档不存在");
			}
			// 显式替换可调整来源名，但不能占用同一知识库中其他稳定文档的来源。
			Long sourceOwnerCount = this.knowledgeDocumentMapper.selectCount(
				new LambdaQueryWrapper<KnowledgeDocumentEntity>()
					.eq(KnowledgeDocumentEntity::getEntCode, entCode)
					.eq(KnowledgeDocumentEntity::getKnowledgeBaseId, knowledgeBaseId)
					.eq(KnowledgeDocumentEntity::getSourceName, sourceName)
					.ne(KnowledgeDocumentEntity::getDocumentId, documentId)
					.ne(KnowledgeDocumentEntity::getStatus, "deleted"));
			if (sourceOwnerCount != null && sourceOwnerCount > 0) {
				throw new IllegalStateException("同名来源已属于其他知识文档");
			}
			return documentId;
		}
		KnowledgeDocumentEntity latest = this.knowledgeDocumentMapper.selectOne(
			new LambdaQueryWrapper<KnowledgeDocumentEntity>()
				.eq(KnowledgeDocumentEntity::getEntCode, entCode)
				.eq(KnowledgeDocumentEntity::getKnowledgeBaseId, knowledgeBaseId)
				.eq(KnowledgeDocumentEntity::getSourceName, sourceName)
				.ne(KnowledgeDocumentEntity::getStatus, "deleted")
				.orderByDesc(KnowledgeDocumentEntity::getVersion)
				.last("LIMIT 1"));
		return latest == null ? UUID.randomUUID().toString() : latest.getDocumentId();
	}

	/**
	 * 查找指定版本记录。
	 *
	 * @param versions 版本记录
	 * @param version  版本号
	 * @return 目标版本
	 * @throws IllegalStateException 版本不存在时抛出 BIZ_ERROR
	 */
	private KnowledgeDocumentEntity findVersion(List<KnowledgeDocumentEntity> versions, int version) {
		return versions.stream()
			.filter(item -> item.getVersion() != null && item.getVersion() == version)
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("知识文档版本不存在"));
	}

	/**
	 * 将稳定文档的版本集合转换为列表项。
	 *
	 * @param versions 同一稳定文档的全部未删除版本
	 * @return 文档列表项
	 */
	private KnowledgeVO.KnowledgeDocumentItem toDocumentItem(List<KnowledgeDocumentEntity> versions) {
		KnowledgeDocumentEntity latest = versions.stream()
			.max(Comparator.comparing(KnowledgeDocumentEntity::getVersion))
			.orElseThrow();
		KnowledgeDocumentEntity ready = versions.stream()
			.filter(version -> "ready".equals(version.getStatus()))
			.max(Comparator.comparing(KnowledgeDocumentEntity::getVersion))
			.orElse(null);
		Integer readyVersion = ready == null ? null : ready.getVersion();
		boolean requiresReindex = ready != null
			&& !EmbeddingModelMetadata.CURRENT_MODEL_ID.equals(ready.getEmbeddingModel());
		return new KnowledgeVO.KnowledgeDocumentItem(latest.getDocumentId(), latest.getKnowledgeBaseId(),
			latest.getSourceName(), latest.getContentType(), latest.getSizeBytes(), latest.getVersion(),
			latest.getStatus(), readyVersion, requiresReindex, safeInt(latest.getChunkCount()),
			latest.getErrorMessage(),
			formatDateTime(latest.getCreatedAt()), formatDateTime(latest.getUpdatedAt()));
	}

	/**
	 * 校验并规范来源名称。
	 *
	 * @param sourceName 原始来源名称
	 * @return 合法来源名称
	 * @throws IllegalArgumentException 来源为空或超长时抛出 PARAM_ERROR
	 */
	private String validateSourceName(String sourceName) {
		String value = sourceName == null ? "" : sourceName.trim();
		if (value.isEmpty()) {
			throw new IllegalArgumentException("文件名不能为空");
		}
		if (value.length() > MAX_SOURCE_NAME_LENGTH) {
			throw new IllegalArgumentException("文件名不能超过 255 个字符");
		}
		return value;
	}

	/**
	 * 规范可空内容类型。
	 *
	 * @param contentType 原始内容类型
	 * @return 限长后的内容类型
	 */
	private String normalizeContentType(String contentType) {
		if (contentType == null || contentType.isBlank()) {
			return "application/octet-stream";
		}
		String value = contentType.trim();
		return value.length() > 100 ? value.substring(0, 100) : value;
	}

	/**
	 * 生成不暴露内部异常内容的安全错误摘要。
	 *
	 * @param error 导入异常
	 * @return 固定的用户可见错误摘要
	 */
	private String safeErrorMessage(Throwable error) {
		// 原始异常只用于服务端异常链路，文档列表不回显地址、SQL 或密钥等内部信息。
		return "文档解析或向量写入失败，请稍后重试";
	}

	/**
	 * 安全转换可空整数。
	 *
	 * @param value 可空整数
	 * @return 非空整数
	 */
	private int safeInt(Integer value) {
		return value == null ? 0 : value;
	}

	/**
	 * 格式化可空时间。
	 *
	 * @param value 时间
	 * @return 统一格式时间或空字符串
	 */
	private String formatDateTime(LocalDateTime value) {
		return value == null ? "" : DATE_TIME_FORMATTER.format(value);
	}

	/**
	 * 查询当前知识库全部可检索文档身份，供请求级标题匹配构建向量过滤条件。
	 *
	 * @param knowledgeBaseId 知识库 ID
	 * @return 当前租户状态为 ready 的文档身份
	 */
	public List<ManagedDocumentMetadata> findReadyDocuments(String knowledgeBaseId) {
		String entCode = TenantContext.requireEntCode();
		return this.knowledgeDocumentMapper.selectList(
			new LambdaQueryWrapper<KnowledgeDocumentEntity>()
				.eq(KnowledgeDocumentEntity::getEntCode, entCode)
				.eq(KnowledgeDocumentEntity::getKnowledgeBaseId, knowledgeBaseId)
				.eq(KnowledgeDocumentEntity::getStatus, "ready")
				.orderByAsc(KnowledgeDocumentEntity::getSourceName)
				.orderByAsc(KnowledgeDocumentEntity::getDocumentId))
			.stream()
			.filter(row -> row.getSourceName() != null && !row.getSourceName().isBlank())
			.map(row -> new ManagedDocumentMetadata(entCode, knowledgeBaseId,
				row.getDocumentId(), row.getVersion(), row.getSourceName()))
			.toList();
	}

	/**
	 * 批量查询当前知识库仍为 ready 的版本及注册来源。
	 *
	 * @param knowledgeBaseId 知识库 ID
	 * @param requested       待校验版本键
	 * @return 当前可检索版本键到注册来源的映射
	 */
	public Map<DocumentVersionKey, String> findReadyVersionSources(String knowledgeBaseId,
			Set<DocumentVersionKey> requested) {
		if (requested == null || requested.isEmpty()) {
			return Map.of();
		}
		String entCode = TenantContext.requireEntCode();
		Set<String> documentIds = requested.stream().map(DocumentVersionKey::documentId).collect(
			java.util.stream.Collectors.toSet());
		List<KnowledgeDocumentEntity> rows = this.knowledgeDocumentMapper.selectList(
			new LambdaQueryWrapper<KnowledgeDocumentEntity>()
				.eq(KnowledgeDocumentEntity::getEntCode, entCode)
				.eq(KnowledgeDocumentEntity::getKnowledgeBaseId, knowledgeBaseId)
				.eq(KnowledgeDocumentEntity::getStatus, "ready")
				.eq(KnowledgeDocumentEntity::getEmbeddingModel,
					EmbeddingModelMetadata.CURRENT_MODEL_ID)
				.in(KnowledgeDocumentEntity::getDocumentId, documentIds));
		Map<DocumentVersionKey, String> readySources = new LinkedHashMap<>();
		for (KnowledgeDocumentEntity row : rows) {
			DocumentVersionKey key = new DocumentVersionKey(row.getDocumentId(), row.getVersion());
			if (requested.contains(key) && row.getSourceName() != null
					&& EmbeddingModelMetadata.CURRENT_MODEL_ID.equals(row.getEmbeddingModel())) {
				readySources.put(key, row.getSourceName());
			}
		}
		return Map.copyOf(readySources);
	}

}
