package com.example.rag.tool.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.rag.config.TenantContext;
import com.example.rag.dao.entity.LlmToolEntity;
import com.example.rag.dao.entity.ToolCallLogEntity;
import com.example.rag.dao.mapper.LlmToolMapper;
import com.example.rag.dao.mapper.ToolCallLogMapper;
import com.example.rag.tool.dynamic.SqlToolValidator;
import com.example.rag.tool.registry.ToolRegistryService;
import com.example.rag.tool.registry.ToolSnapshot;
import com.example.rag.vo.AdminVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * LLM Tool 管理服务测试。
 */
public class ToolManagementServiceTest {

	/** 动态 Tool 定义 Mapper。 */
	private LlmToolMapper llmToolMapper;

	/** Tool 调用流水 Mapper。 */
	private ToolCallLogMapper toolCallLogMapper;

	/** Tool 注册服务。 */
	private ToolRegistryService toolRegistryService;

	/** 待测试服务。 */
	private ToolManagementService service;

	/**
	 * 初始化测试依赖。
	 */
	@BeforeEach
	public void setUp() {
		llmToolMapper = mock(LlmToolMapper.class);
		toolCallLogMapper = mock(ToolCallLogMapper.class);
		toolRegistryService = mock(ToolRegistryService.class);
		service = new ToolManagementService(llmToolMapper, toolCallLogMapper, new SqlToolValidator(),
			toolRegistryService);
	}

	/**
	 * 验证新增 Tool 成功后会刷新 Tool 快照。
	 */
	@Test
	public void shouldSaveToolAndRefreshSnapshot() {
		when(llmToolMapper.selectCount(any())).thenReturn(0L);
		when(llmToolMapper.insert(any(LlmToolEntity.class))).thenReturn(1);
		when(toolRegistryService.refresh()).thenReturn(new ToolSnapshot(2L, List.of(), List.of()));

		boolean result = service.saveTool(toolItem(null, "query_order", "SELECT * FROM b_sales_order"));

		assertThat(result).isTrue();
		verify(llmToolMapper).insert(any(LlmToolEntity.class));
		verify(toolRegistryService).refresh();
	}

	/**
	 * 验证事务同步开启时，Tool 快照在提交后再刷新。
	 */
	@Test
	public void shouldRefreshSnapshotAfterTransactionCommit() {
		when(llmToolMapper.selectCount(any())).thenReturn(0L);
		when(llmToolMapper.insert(any(LlmToolEntity.class))).thenReturn(1);
		when(toolRegistryService.refresh()).thenReturn(new ToolSnapshot(2L, List.of(), List.of()));
		TransactionSynchronizationManager.initSynchronization();
		try {
			boolean result = service.saveTool(toolItem(null, "query_order", "SELECT * FROM b_sales_order"));

			assertThat(result).isTrue();
			verify(toolRegistryService, never()).refresh();
			TransactionSynchronizationManager.getSynchronizations()
				.forEach(TransactionSynchronization::afterCommit);
			verify(toolRegistryService).refresh();
		}
		finally {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	/**
	 * 验证 Tool 名称重复时拒绝保存。
	 */
	@Test
	public void shouldRejectDuplicateToolName() {
		when(llmToolMapper.selectCount(any())).thenReturn(1L);

		assertThatThrownBy(() -> service.saveTool(toolItem(null, "query_order", "SELECT * FROM b_sales_order")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Tool 名称已存在");
	}

	/**
	 * 验证动态 Tool 不能覆盖代码内置 Tool 名称。
	 */
	@Test
	public void shouldRejectCodeToolNameCollision() {
		ToolCallback codeTool = codeTool("query_order");
		when(toolRegistryService.buildCodeToolCallbacks()).thenReturn(List.of(codeTool));
		when(llmToolMapper.selectCount(any())).thenReturn(0L);
		when(llmToolMapper.insert(any(LlmToolEntity.class))).thenReturn(1);

		assertThatThrownBy(() -> service.saveTool(toolItem(null, "query_order", "SELECT * FROM b_sales_order")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Tool 名称已存在");
	}

	/**
	 * 验证刷新失败会向上返回错误。
	 */
	@Test
	public void shouldPropagateRefreshFailure() {
		when(llmToolMapper.selectCount(any())).thenReturn(0L);
		when(llmToolMapper.insert(any(LlmToolEntity.class))).thenReturn(1);
		when(toolRegistryService.refresh()).thenThrow(new IllegalStateException("刷新失败"));

		assertThatThrownBy(() -> service.saveTool(toolItem(null, "query_order", "SELECT * FROM b_sales_order")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("刷新失败");
	}

	/**
	 * 验证写操作 SQL 会被管理服务拒绝。
	 */
	@Test
	public void shouldRejectWriteSql() {
		assertThatThrownBy(() -> service.saveTool(toolItem(null, "query_order", "UPDATE b_sales_order SET status = 1")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("只允许配置只读查询");
	}

	/**
	 * 验证更新不存在的 Tool 时返回业务错误。
	 */
	@Test
	public void shouldRejectMissingToolWhenUpdating() {
		when(llmToolMapper.selectCount(any())).thenReturn(0L);
		when(llmToolMapper.updateById(any(LlmToolEntity.class))).thenReturn(0);

		assertThatThrownBy(() -> service.updateTool(toolItem(99L, "query_order", "SELECT * FROM b_sales_order")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("Tool 不存在");
	}

	/**
	 * 验证删除不存在的 Tool 时返回业务错误。
	 */
	@Test
	public void shouldRejectMissingToolWhenDeleting() {
		when(llmToolMapper.deleteById(99L)).thenReturn(0);

		assertThatThrownBy(() -> service.deleteTool(99L))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("Tool 不存在");
	}

	/**
	 * 验证调用日志查询会带上分页和 Tool 名称过滤。
	 */
	@Test
	@SuppressWarnings("unchecked")
	public void shouldBuildPagedCallLogQueryWithToolName() {
		when(toolCallLogMapper.selectList(any())).thenReturn(List.of());
		ArgumentCaptor<QueryWrapper<ToolCallLogEntity>> captor =
			ArgumentCaptor.forClass(QueryWrapper.class);

		TenantContext.setEntCode("ENT001");
		try {
			service.listCallLogs(1, 20, "query_order");
		}
		finally {
			TenantContext.clear();
		}

		verify(toolCallLogMapper).selectList(captor.capture());
		assertThat(captor.getValue().getSqlSegment())
			.contains("ent_code")
			.contains("tool_name")
			.contains("LIMIT 20, 20");
	}

	/**
	 * 构造测试用 Tool 管理 VO。
	 *
	 * @param id          主键 ID
	 * @param toolName    Tool 名称
	 * @param sqlTemplate SQL 模板
	 * @return Tool 管理 VO
	 */
	private AdminVO.ToolItem toolItem(Long id, String toolName, String sqlTemplate) {
		return new AdminVO.ToolItem(id, toolName, "查询测试数据",
			"""
			{
			  "type": "object",
			  "properties": {}
			}
			""",
			sqlTemplate, null, 50, "active", null, null, null);
	}

	/**
	 * 构造指定名称的代码 ToolCallback。
	 *
	 * @param toolName Tool 名称
	 * @return 代码 ToolCallback
	 */
	private ToolCallback codeTool(String toolName) {
		ToolDefinition definition = mock(ToolDefinition.class);
		ToolCallback callback = mock(ToolCallback.class);
		when(definition.name()).thenReturn(toolName);
		when(callback.getToolDefinition()).thenReturn(definition);
		return callback;
	}

}
