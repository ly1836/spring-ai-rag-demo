package com.example.rag.knowledge;

import java.util.List;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.example.rag.config.TenantContext;
import com.example.rag.dao.entity.KnowledgeBaseEntity;
import com.example.rag.dao.mapper.KnowledgeBaseMapper;
import com.example.rag.dao.mapper.KnowledgeDocumentMapper;
import com.example.rag.dao.mapper.TenantMapper;
import com.example.rag.knowledge.dto.KnowledgeDocumentCount;
import com.example.rag.vo.KnowledgeVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库管理服务测试。
 */
class KnowledgeBaseServiceTest {

	private KnowledgeBaseMapper knowledgeBaseMapper;

	private KnowledgeDocumentMapper knowledgeDocumentMapper;

	private TenantMapper tenantMapper;

	private KnowledgeBaseService service;

	/**
	 * 初始化当前租户和测试依赖。
	 */
	@BeforeEach
	public void setUp() {
		this.knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
		this.knowledgeDocumentMapper = mock(KnowledgeDocumentMapper.class);
		this.tenantMapper = mock(TenantMapper.class);
		this.service = new KnowledgeBaseService(
			this.knowledgeBaseMapper, this.knowledgeDocumentMapper, this.tenantMapper);
		TenantContext.setEntCode("ENT001");
		TenantContext.setUserId("U001");
		when(this.tenantMapper.selectIdForUpdate("ENT001")).thenReturn(1L);
	}

	/**
	 * 清理测试租户上下文。
	 */
	@AfterEach
	public void tearDown() {
		TenantContext.clear();
	}

	/**
	 * 验证首次查询会懒创建唯一默认知识库。
	 */
	@Test
	@SuppressWarnings("unchecked")
	public void shouldCreateDefaultKnowledgeBaseOnFirstList() {
		when(this.knowledgeBaseMapper.selectList(any(Wrapper.class)))
			.thenReturn(List.of(), List.of());
		when(this.knowledgeDocumentMapper.selectDocumentCounts("ENT001")).thenReturn(List.of());
		ArgumentCaptor<KnowledgeBaseEntity> entityCaptor = ArgumentCaptor.forClass(KnowledgeBaseEntity.class);

		List<KnowledgeVO.KnowledgeBaseItem> result = this.service.listKnowledgeBases();

		verify(this.knowledgeBaseMapper).insert(entityCaptor.capture());
		assertThat(entityCaptor.getValue().getEntCode()).isEqualTo("ENT001");
		assertThat(entityCaptor.getValue().getName()).isEqualTo("默认知识库");
		assertThat(entityCaptor.getValue().getIsDefault()).isTrue();
		assertThat(result).isEmpty();
	}

	/**
	 * 验证非法名称在写库前被拒绝。
	 */
	@Test
	public void shouldRejectBlankKnowledgeBaseName() {
		assertThatThrownBy(() -> this.service.createKnowledgeBase(
			new KnowledgeVO.CreateKnowledgeBaseRequest(" ", "说明")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("知识库名称不能为空");

		verify(this.knowledgeBaseMapper, never()).insert(any(KnowledgeBaseEntity.class));
	}

	/**
	 * 验证跨租户或不存在知识库统一按不存在处理。
	 */
	@Test
	public void shouldHideKnowledgeBaseOutsideCurrentTenant() {
		when(this.knowledgeBaseMapper.selectOne(any())).thenReturn(null);

		assertThatThrownBy(() -> this.service.requireKnowledgeBase("other-base"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("知识库不存在");
	}

	/**
	 * 验证默认知识库不能停用。
	 */
	@Test
	public void shouldRejectDisablingDefaultKnowledgeBase() {
		KnowledgeBaseEntity entity = knowledgeBase("kb-default", true, "active");
		when(this.knowledgeBaseMapper.selectForUpdate("ENT001", "kb-default")).thenReturn(entity);

		assertThatThrownBy(() -> this.service.updateKnowledgeBase("kb-default",
			new KnowledgeVO.UpdateKnowledgeBaseRequest(null, null, "inactive", null)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("默认知识库不能停用");
	}

	/**
	 * 验证非空知识库不会被删除。
	 */
	@Test
	public void shouldRejectDeletingNonEmptyKnowledgeBase() {
		KnowledgeBaseEntity entity = knowledgeBase("kb-1", false, "active");
		when(this.knowledgeBaseMapper.selectForUpdate("ENT001", "kb-1")).thenReturn(entity);
		when(this.knowledgeDocumentMapper.selectCount(any())).thenReturn(1L);

		assertThatThrownBy(() -> this.service.deleteKnowledgeBase("kb-1"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("知识库仍包含文档，不能删除");
	}

	/**
	 * 构建测试知识库实体。
	 *
	 * @param knowledgeBaseId 知识库 ID
	 * @param isDefault       是否默认库
	 * @param status          状态
	 * @return 测试实体
	 */
	private KnowledgeBaseEntity knowledgeBase(String knowledgeBaseId, boolean isDefault, String status) {
		KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
		entity.setId(2L);
		entity.setKnowledgeBaseId(knowledgeBaseId);
		entity.setEntCode("ENT001");
		entity.setName("测试知识库");
		entity.setIsDefault(isDefault);
		entity.setStatus(status);
		return entity;
	}

	/**
	 * 验证并发异常留下多个默认标记时会在租户锁内归一化。
	 */
	@Test
	@SuppressWarnings("unchecked")
	public void shouldNormalizeDuplicateDefaultFlags() {
		KnowledgeBaseEntity first = knowledgeBase("kb-1", true, "active");
		KnowledgeBaseEntity second = knowledgeBase("kb-2", true, "active");
		when(this.knowledgeBaseMapper.selectList(any(Wrapper.class)))
			.thenReturn(List.of(first, second), List.of(first, second));
		when(this.knowledgeDocumentMapper.selectDocumentCounts("ENT001")).thenReturn(List.of());

		this.service.listKnowledgeBases();

		assertThat(first.getIsDefault()).isTrue();
		assertThat(second.getIsDefault()).isFalse();
		verify(this.tenantMapper).selectIdForUpdate("ENT001");
		verify(this.knowledgeBaseMapper).updateById(second);
	}

	/**
	 * 验证首次直接创建知识库时先补齐默认库，再执行重名校验和普通库写入。
	 */
	@Test
	public void shouldSerializeKnowledgeBaseCreationWithTenantLock() {
		when(this.knowledgeBaseMapper.selectList(any())).thenReturn(List.of());
		when(this.knowledgeBaseMapper.selectCount(any())).thenReturn(0L);
		ArgumentCaptor<KnowledgeBaseEntity> entityCaptor = ArgumentCaptor.forClass(KnowledgeBaseEntity.class);

		this.service.createKnowledgeBase(
			new KnowledgeVO.CreateKnowledgeBaseRequest("研发资料", "说明"));

		InOrder ordered = inOrder(this.tenantMapper, this.knowledgeBaseMapper);
		ordered.verify(this.tenantMapper).selectIdForUpdate("ENT001");
		ordered.verify(this.knowledgeBaseMapper).selectList(any());
		ordered.verify(this.knowledgeBaseMapper).insert(entityCaptor.capture());
		ordered.verify(this.knowledgeBaseMapper).selectCount(any());
		ordered.verify(this.knowledgeBaseMapper).insert(entityCaptor.capture());
		assertThat(entityCaptor.getAllValues()).extracting(KnowledgeBaseEntity::getIsDefault)
			.containsExactly(true, false);
		assertThat(entityCaptor.getAllValues()).extracting(KnowledgeBaseEntity::getName)
			.containsExactly("默认知识库", "研发资料");
	}

	/**
	 * 验证知识库列表使用数据库聚合后的稳定文档数量。
	 */
	@Test
	@SuppressWarnings("unchecked")
	public void shouldUseAggregatedDocumentCounts() {
		KnowledgeBaseEntity entity = knowledgeBase("kb-1", true, "active");
		when(this.knowledgeBaseMapper.selectList(any(Wrapper.class)))
			.thenReturn(List.of(entity), List.of(entity));
		when(this.knowledgeDocumentMapper.selectDocumentCounts("ENT001"))
			.thenReturn(List.of(new KnowledgeDocumentCount("kb-1", 3L)));

		List<KnowledgeVO.KnowledgeBaseItem> result = this.service.listKnowledgeBases();

		assertThat(result).singleElement()
			.extracting(KnowledgeVO.KnowledgeBaseItem::documentCount)
			.isEqualTo(3);
		verify(this.knowledgeDocumentMapper).selectDocumentCounts("ENT001");
		verify(this.tenantMapper, never()).selectIdForUpdate("ENT001");
	}

	/**
	 * 验证系统保留的默认知识库名称不能被创建为普通知识库。
	 */
	@Test
	public void shouldRejectReservedDefaultKnowledgeBaseName() {
		assertThatThrownBy(() -> this.service.createKnowledgeBase(
			new KnowledgeVO.CreateKnowledgeBaseRequest("默认知识库", "普通说明")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("默认知识库名称为系统保留名称");

		verify(this.knowledgeBaseMapper, never()).insert(any(KnowledgeBaseEntity.class));
		verify(this.tenantMapper, never()).selectIdForUpdate("ENT001");
	}

	/**
	 * 验证普通知识库不能通过重命名占用系统默认知识库名称。
	 */
	@Test
	public void shouldRejectRenamingToReservedDefaultKnowledgeBaseName() {
		KnowledgeBaseEntity entity = knowledgeBase("kb-1", false, "active");
		when(this.knowledgeBaseMapper.selectForUpdate("ENT001", "kb-1")).thenReturn(entity);

		assertThatThrownBy(() -> this.service.updateKnowledgeBase("kb-1",
			new KnowledgeVO.UpdateKnowledgeBaseRequest("默认知识库", null, null, null)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("默认知识库名称为系统保留名称");

		verify(this.knowledgeBaseMapper, never()).updateById(entity);
	}

}
