package com.example.rag.knowledge;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.rag.config.TenantContext;
import com.example.rag.dao.entity.KnowledgeBaseEntity;
import com.example.rag.dao.entity.KnowledgeDocumentEntity;
import com.example.rag.dao.mapper.KnowledgeBaseMapper;
import com.example.rag.dao.mapper.KnowledgeDocumentMapper;
import com.example.rag.dao.mapper.TenantMapper;
import com.example.rag.knowledge.dto.KnowledgeDocumentCount;
import com.example.rag.vo.KnowledgeVO;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 租户知识库管理服务。
 */
@Service
public class KnowledgeBaseService {

	private static final DateTimeFormatter DATE_TIME_FORMATTER =
		DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private static final String DEFAULT_KNOWLEDGE_BASE_NAME = "默认知识库";

	private final KnowledgeBaseMapper knowledgeBaseMapper;

	private final KnowledgeDocumentMapper knowledgeDocumentMapper;

	private final TenantMapper tenantMapper;

	/**
	 * 创建知识库管理服务。
	 *
	 * @param knowledgeBaseMapper     知识库 Mapper
	 * @param knowledgeDocumentMapper 文档版本 Mapper
	 * @param tenantMapper            租户 Mapper
	 */
	public KnowledgeBaseService(KnowledgeBaseMapper knowledgeBaseMapper,
			KnowledgeDocumentMapper knowledgeDocumentMapper, TenantMapper tenantMapper) {
		this.knowledgeBaseMapper = knowledgeBaseMapper;
		this.knowledgeDocumentMapper = knowledgeDocumentMapper;
		this.tenantMapper = tenantMapper;
	}

	/**
	 * 查询当前租户知识库列表，并在首次访问时创建默认知识库。
	 *
	 * @return 知识库列表
	 * @throws IllegalStateException 当前租户不存在时抛出 BIZ_ERROR
	 */
	@Transactional(transactionManager = "erpTransactionManager")
	public List<KnowledgeVO.KnowledgeBaseItem> listKnowledgeBases() {
		String entCode = TenantContext.requireEntCode();
		resolveDefaultKnowledgeBase(entCode);
		Map<String, Integer> documentCounts = countDocuments(entCode);
		return this.knowledgeBaseMapper.selectList(new LambdaQueryWrapper<KnowledgeBaseEntity>()
			.eq(KnowledgeBaseEntity::getEntCode, entCode)
			.ne(KnowledgeBaseEntity::getStatus, "deleted")
			.orderByDesc(KnowledgeBaseEntity::getIsDefault)
			.orderByAsc(KnowledgeBaseEntity::getCreatedAt)).stream()
			.map(entity -> toItem(entity, documentCounts.getOrDefault(entity.getKnowledgeBaseId(), 0)))
			.toList();
	}

	/**
	 * 创建当前租户的普通知识库。
	 *
	 * @param request 创建请求
	 * @return 新知识库
	 * @throws IllegalArgumentException 参数非法时抛出 PARAM_ERROR
	 * @throws IllegalStateException 同名知识库已存在时抛出 BIZ_ERROR
	 */
	@Transactional(transactionManager = "erpTransactionManager")
	public KnowledgeVO.KnowledgeBaseItem createKnowledgeBase(KnowledgeVO.CreateKnowledgeBaseRequest request) {
		String entCode = TenantContext.requireEntCode();
		String name = validateName(request.name());
		String description = validateDescription(request.description());
		ensureReservedNameAvailable(name, null);
		// 创建入口也先补齐默认库，并复用同一租户锁保证默认库和名称唯一。
		ensureDefaultKnowledgeBaseLocked(entCode);
		ensureNameAvailable(entCode, name, null);
		KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
		entity.setKnowledgeBaseId(UUID.randomUUID().toString());
		entity.setEntCode(entCode);
		entity.setName(name);
		entity.setDescription(description);
		entity.setStatus("active");
		entity.setIsDefault(false);
		entity.setCreatedBy(TenantContext.getUserIdOrDefault());
		this.knowledgeBaseMapper.insert(entity);
		return toItem(entity, 0);
	}

	/**
	 * 更新当前租户知识库。
	 *
	 * @param knowledgeBaseId 知识库 ID
	 * @param request         更新请求
	 * @return 更新后的知识库
	 * @throws IllegalArgumentException 参数非法时抛出 PARAM_ERROR
	 * @throws IllegalStateException 知识库不存在或状态冲突时抛出 BIZ_ERROR
	 */
	@Transactional(transactionManager = "erpTransactionManager")
	public KnowledgeVO.KnowledgeBaseItem updateKnowledgeBase(String knowledgeBaseId,
			KnowledgeVO.UpdateKnowledgeBaseRequest request) {
		String entCode = TenantContext.requireEntCode();
		KnowledgeBaseEntity entity = requireKnowledgeBaseForUpdate(knowledgeBaseId);
		if (request.name() != null) {
			String name = validateName(request.name());
			ensureReservedNameAvailable(name, entity);
			ensureNameAvailable(entCode, name, knowledgeBaseId);
			entity.setName(name);
		}
		if (request.description() != null) {
			entity.setDescription(validateDescription(request.description()));
		}
		if (request.status() != null) {
			String status = validateStatus(request.status());
			if (Boolean.TRUE.equals(entity.getIsDefault()) && "inactive".equals(status)) {
				throw new IllegalStateException("默认知识库不能停用");
			}
			if (Boolean.TRUE.equals(request.isDefault()) && "inactive".equals(status)) {
				throw new IllegalArgumentException("默认知识库状态必须为 active");
			}
			entity.setStatus(status);
		}
		if (Boolean.TRUE.equals(request.isDefault())) {
			clearDefaultFlag(entCode);
			entity.setIsDefault(true);
			entity.setStatus("active");
		}
		else if (Boolean.FALSE.equals(request.isDefault()) && Boolean.TRUE.equals(entity.getIsDefault())) {
			throw new IllegalStateException("默认知识库不能直接取消默认标记");
		}
		this.knowledgeBaseMapper.updateById(entity);
		return toItem(entity, countDocuments(entCode).getOrDefault(knowledgeBaseId, 0));
	}

	/**
	 * 删除当前租户空的非默认知识库。
	 *
	 * @param knowledgeBaseId 知识库 ID
	 * @throws IllegalStateException 知识库不存在、为默认库或仍有文档时抛出 BIZ_ERROR
	 */
	@Transactional(transactionManager = "erpTransactionManager")
	public void deleteKnowledgeBase(String knowledgeBaseId) {
		String entCode = TenantContext.requireEntCode();
		KnowledgeBaseEntity entity = requireKnowledgeBaseForUpdate(knowledgeBaseId);
		if (Boolean.TRUE.equals(entity.getIsDefault())) {
			throw new IllegalStateException("默认知识库不能删除");
		}
		Long documentCount = this.knowledgeDocumentMapper.selectCount(
			new LambdaQueryWrapper<KnowledgeDocumentEntity>()
				.eq(KnowledgeDocumentEntity::getEntCode, entCode)
				.eq(KnowledgeDocumentEntity::getKnowledgeBaseId, knowledgeBaseId)
				.ne(KnowledgeDocumentEntity::getStatus, "deleted"));
		if (documentCount != null && documentCount > 0) {
			throw new IllegalStateException("知识库仍包含文档，不能删除");
		}
		entity.setStatus("deleted");
		entity.setIsDefault(false);
		this.knowledgeBaseMapper.updateById(entity);
	}

	/**
	 * 解析当前租户可用于检索或导入的知识库。
	 *
	 * @param knowledgeBaseId 可空知识库 ID
	 * @return 状态为 active 的知识库
	 * @throws IllegalStateException 知识库不存在或已停用时抛出 BIZ_ERROR
	 */
	@Transactional(transactionManager = "erpTransactionManager")
	public KnowledgeBaseEntity resolveActiveKnowledgeBase(String knowledgeBaseId) {
		KnowledgeBaseEntity entity = knowledgeBaseId == null || knowledgeBaseId.isBlank()
			? resolveDefaultKnowledgeBase(TenantContext.requireEntCode())
			: requireKnowledgeBase(knowledgeBaseId);
		if (!"active".equals(entity.getStatus())) {
			throw new IllegalStateException("知识库已停用");
		}
		return entity;
	}

	/**
	 * 查询当前租户知识库，不存在或已删除时按不存在处理。
	 *
	 * @param knowledgeBaseId 知识库 ID
	 * @return 知识库实体
	 * @throws IllegalStateException 知识库不存在时抛出 BIZ_ERROR
	 */
	public KnowledgeBaseEntity requireKnowledgeBase(String knowledgeBaseId) {
		if (knowledgeBaseId == null || knowledgeBaseId.isBlank()) {
			throw new IllegalArgumentException("知识库 ID 不能为空");
		}
		String entCode = TenantContext.requireEntCode();
		KnowledgeBaseEntity entity = this.knowledgeBaseMapper.selectOne(
			new LambdaQueryWrapper<KnowledgeBaseEntity>()
				.eq(KnowledgeBaseEntity::getEntCode, entCode)
				.eq(KnowledgeBaseEntity::getKnowledgeBaseId, knowledgeBaseId)
				.ne(KnowledgeBaseEntity::getStatus, "deleted")
				.last("LIMIT 1"));
		if (entity == null) {
			throw new IllegalStateException("知识库不存在");
		}
		return entity;
	}

	/**
	 * 锁定租户并创建或返回默认知识库。
	 *
	 * @param entCode 租户编码
	 * @return 默认知识库
	 * @throws IllegalStateException 当前租户不存在时抛出 BIZ_ERROR
	 */
	private KnowledgeBaseEntity ensureDefaultKnowledgeBaseLocked(String entCode) {
		lockTenant(entCode);
		List<KnowledgeBaseEntity> defaults = selectDefaultKnowledgeBases(entCode);
		if (!defaults.isEmpty()) {
			KnowledgeBaseEntity selected = defaults.get(0);
			if (!"active".equals(selected.getStatus())) {
				selected.setStatus("active");
				this.knowledgeBaseMapper.updateById(selected);
			}
			for (int index = 1; index < defaults.size(); index++) {
				KnowledgeBaseEntity duplicate = defaults.get(index);
				duplicate.setIsDefault(false);
				this.knowledgeBaseMapper.updateById(duplicate);
			}
			return selected;
		}
		KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
		entity.setKnowledgeBaseId(UUID.randomUUID().toString());
		entity.setEntCode(entCode);
		entity.setName(DEFAULT_KNOWLEDGE_BASE_NAME);
		entity.setDescription("系统默认知识库");
		entity.setStatus("active");
		entity.setIsDefault(true);
		entity.setCreatedBy(TenantContext.getUserIdOrDefault());
		this.knowledgeBaseMapper.insert(entity);
		return entity;
	}

	/**
	 * 锁定当前租户记录。
	 *
	 * @param entCode 租户编码
	 * @throws IllegalStateException 当前租户不存在时抛出 BIZ_ERROR
	 */
	private void lockTenant(String entCode) {
		if (this.tenantMapper.selectIdForUpdate(entCode) == null) {
			throw new IllegalStateException("租户不存在，无法管理知识库");
		}
	}

	/**
	 * 清除当前租户已有默认知识库标记。
	 *
	 * @param entCode 租户编码
	 */
	private void clearDefaultFlag(String entCode) {
		this.knowledgeBaseMapper.update(null, new LambdaUpdateWrapper<KnowledgeBaseEntity>()
			.eq(KnowledgeBaseEntity::getEntCode, entCode)
			.eq(KnowledgeBaseEntity::getIsDefault, true)
			.set(KnowledgeBaseEntity::getIsDefault, false));
	}

	/**
	 * 统计每个知识库的稳定文档数量。
	 *
	 * @param entCode 租户编码
	 * @return 知识库 ID 到稳定文档数量的映射
	 */
	private Map<String, Integer> countDocuments(String entCode) {
		Map<String, Integer> counts = new HashMap<>();
		for (KnowledgeDocumentCount row : this.knowledgeDocumentMapper.selectDocumentCounts(entCode)) {
			long count = row.documentCount() == null ? 0L : row.documentCount();
			counts.put(row.knowledgeBaseId(), count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count);
		}
		return counts;
	}

	/**
	 * 校验同租户未删除知识库名称唯一。
	 *
	 * @param entCode        租户编码
	 * @param name           知识库名称
	 * @param excludedBaseId 更新时排除的知识库 ID
	 * @throws IllegalStateException 名称已存在时抛出 BIZ_ERROR
	 */
	private void ensureNameAvailable(String entCode, String name, String excludedBaseId) {
		LambdaQueryWrapper<KnowledgeBaseEntity> query = new LambdaQueryWrapper<KnowledgeBaseEntity>()
			.eq(KnowledgeBaseEntity::getEntCode, entCode)
			.eq(KnowledgeBaseEntity::getName, name)
			.ne(KnowledgeBaseEntity::getStatus, "deleted");
		if (excludedBaseId != null) {
			query.ne(KnowledgeBaseEntity::getKnowledgeBaseId, excludedBaseId);
		}
		if (this.knowledgeBaseMapper.selectCount(query) > 0) {
			throw new IllegalStateException("同名知识库已存在");
		}
	}

	/**
	 * 校验并规范知识库名称。
	 *
	 * @param name 原始名称
	 * @return 去除首尾空白后的名称
	 * @throws IllegalArgumentException 名称为空或超长时抛出 PARAM_ERROR
	 */
	private String validateName(String name) {
		String value = name == null ? "" : name.trim();
		if (value.isEmpty()) {
			throw new IllegalArgumentException("知识库名称不能为空");
		}
		if (value.length() > 100) {
			throw new IllegalArgumentException("知识库名称不能超过 100 个字符");
		}
		return value;
	}

	/**
	 * 校验知识库说明长度。
	 *
	 * @param description 原始说明
	 * @return 去除首尾空白后的说明
	 * @throws IllegalArgumentException 说明超长时抛出 PARAM_ERROR
	 */
	private String validateDescription(String description) {
		String value = description == null ? "" : description.trim();
		if (value.length() > 500) {
			throw new IllegalArgumentException("知识库说明不能超过 500 个字符");
		}
		return value;
	}

	/**
	 * 校验知识库状态。
	 *
	 * @param status 原始状态
	 * @return 合法状态
	 * @throws IllegalArgumentException 状态非法时抛出 PARAM_ERROR
	 */
	private String validateStatus(String status) {
		String value = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
		if (!Set.of("active", "inactive").contains(value)) {
			throw new IllegalArgumentException("知识库状态必须为 active 或 inactive");
		}
		return value;
	}

	/**
	 * 转换知识库响应对象。
	 *
	 * @param entity        知识库实体
	 * @param documentCount 稳定文档数量
	 * @return 知识库列表项
	 */
	private KnowledgeVO.KnowledgeBaseItem toItem(KnowledgeBaseEntity entity, int documentCount) {
		return new KnowledgeVO.KnowledgeBaseItem(entity.getKnowledgeBaseId(), entity.getName(),
			entity.getDescription(), entity.getStatus(), Boolean.TRUE.equals(entity.getIsDefault()),
			documentCount, formatDateTime(entity.getCreatedAt()), formatDateTime(entity.getUpdatedAt()));
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
	 * 在租户锁内解析并锁定可用于导入的知识库。
	 *
	 * @param knowledgeBaseId 可空知识库 ID
	 * @return 最新且状态为 active 的知识库
	 * @throws IllegalStateException 知识库不存在或已停用时抛出 BIZ_ERROR
	 */
	@Transactional(transactionManager = "erpTransactionManager")
	public KnowledgeBaseEntity resolveActiveKnowledgeBaseForUpdate(String knowledgeBaseId) {
		KnowledgeBaseEntity entity = knowledgeBaseId == null || knowledgeBaseId.isBlank()
			? ensureDefaultKnowledgeBaseLocked(TenantContext.requireEntCode())
			: requireKnowledgeBaseForUpdate(knowledgeBaseId);
		if (!"active".equals(entity.getStatus())) {
			throw new IllegalStateException("知识库已停用");
		}
		return entity;
	}

	/**
	 * 在租户锁内读取并锁定当前租户知识库的最新状态。
	 *
	 * @param knowledgeBaseId 知识库 ID
	 * @return 最新知识库记录
	 * @throws IllegalArgumentException 知识库 ID 为空时抛出 PARAM_ERROR
	 * @throws IllegalStateException 知识库不存在时抛出 BIZ_ERROR
	 */
	@Transactional(transactionManager = "erpTransactionManager")
	public KnowledgeBaseEntity requireKnowledgeBaseForUpdate(String knowledgeBaseId) {
		if (knowledgeBaseId == null || knowledgeBaseId.isBlank()) {
			throw new IllegalArgumentException("知识库 ID 不能为空");
		}
		String entCode = TenantContext.requireEntCode();
		lockTenant(entCode);
		KnowledgeBaseEntity entity = this.knowledgeBaseMapper.selectForUpdate(entCode, knowledgeBaseId);
		if (entity == null) {
			throw new IllegalStateException("知识库不存在");
		}
		return entity;
	}

	/**
	 * 限制系统默认知识库名称只能由懒创建流程首次写入。
	 *
	 * @param name          待使用的知识库名称
	 * @param currentEntity 可空当前知识库
	 * @throws IllegalStateException 普通知识库尝试占用系统保留名称时抛出 BIZ_ERROR
	 */
	private void ensureReservedNameAvailable(String name, KnowledgeBaseEntity currentEntity) {
		boolean keepsSystemName = currentEntity != null
			&& DEFAULT_KNOWLEDGE_BASE_NAME.equals(currentEntity.getName());
		if (DEFAULT_KNOWLEDGE_BASE_NAME.equals(name) && !keepsSystemName) {
			throw new IllegalStateException("默认知识库名称为系统保留名称");
		}
	}

	/**
	 * 读取已有默认知识库，仅在缺失或状态异常时进入租户锁修复流程。
	 *
	 * @param entCode 租户编码
	 * @return 唯一且可用的默认知识库
	 * @throws IllegalStateException 当前租户不存在时抛出 BIZ_ERROR
	 */
	private KnowledgeBaseEntity resolveDefaultKnowledgeBase(String entCode) {
		List<KnowledgeBaseEntity> defaults = selectDefaultKnowledgeBases(entCode);
		if (defaults.size() == 1 && "active".equals(defaults.get(0).getStatus())) {
			return defaults.get(0);
		}
		// 首次创建、重复默认标记或异常状态仍在租户锁内重新读取和修复。
		return ensureDefaultKnowledgeBaseLocked(entCode);
	}

	/**
	 * 查询当前租户全部未删除的默认知识库记录。
	 *
	 * @param entCode 租户编码
	 * @return 按创建时间排序的默认知识库
	 */
	private List<KnowledgeBaseEntity> selectDefaultKnowledgeBases(String entCode) {
		return this.knowledgeBaseMapper.selectList(
			new LambdaQueryWrapper<KnowledgeBaseEntity>()
				.eq(KnowledgeBaseEntity::getEntCode, entCode)
				.eq(KnowledgeBaseEntity::getIsDefault, true)
				.ne(KnowledgeBaseEntity::getStatus, "deleted")
				.orderByAsc(KnowledgeBaseEntity::getCreatedAt));
	}

}
