package com.example.rag.tool.admin;

import java.time.LocalDateTime;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * LLM Tool 管理服务。
 */
@Service
public class ToolManagementService {

	/** 动态 Tool 定义 Mapper。 */
	private final LlmToolMapper llmToolMapper;

	/** Tool 调用流水 Mapper。 */
	private final ToolCallLogMapper toolCallLogMapper;

	/** 动态 SQL Tool 校验器。 */
	private final SqlToolValidator sqlToolValidator;

	/** Tool 注册服务。 */
	private final ToolRegistryService toolRegistryService;

	/**
	 * 创建 LLM Tool 管理服务。
	 *
	 * @param llmToolMapper      动态 Tool 定义 Mapper
	 * @param toolCallLogMapper  Tool 调用流水 Mapper
	 * @param sqlToolValidator   动态 SQL Tool 校验器
	 * @param toolRegistryService Tool 注册服务
	 */
	public ToolManagementService(LlmToolMapper llmToolMapper, ToolCallLogMapper toolCallLogMapper,
			SqlToolValidator sqlToolValidator, ToolRegistryService toolRegistryService) {
		this.llmToolMapper = llmToolMapper;
		this.toolCallLogMapper = toolCallLogMapper;
		this.sqlToolValidator = sqlToolValidator;
		this.toolRegistryService = toolRegistryService;
	}

	/**
	 * 查询动态 Tool 定义列表。
	 *
	 * @return 动态 Tool 定义列表
	 */
	public List<AdminVO.ToolItem> listTools() {
		return llmToolMapper.selectList(new LambdaQueryWrapper<LlmToolEntity>()
			.orderByDesc(LlmToolEntity::getUpdatedAt)
			.orderByAsc(LlmToolEntity::getToolName)).stream()
			.map(this::toToolItem)
			.toList();
	}

	/**
	 * 新增动态 Tool 定义，并刷新当前 Tool 快照。
	 *
	 * @param item 动态 Tool 定义
	 * @return 是否保存成功
	 */
	@Transactional(transactionManager = "erpTransactionManager")
	public boolean saveTool(AdminVO.ToolItem item) {
		LlmToolEntity entity = toToolEntity(item);
		sqlToolValidator.validateTool(entity);
		ensureToolNameUnique(entity);
		boolean saved = llmToolMapper.insert(entity) > 0;
		if (!saved) {
			throw new IllegalStateException("Tool 保存失败");
		}
		refreshAfterCommit();
		return saved;
	}

	/**
	 * 更新动态 Tool 定义，并刷新当前 Tool 快照。
	 *
	 * @param item 动态 Tool 定义
	 * @return 是否更新成功
	 */
	@Transactional(transactionManager = "erpTransactionManager")
	public boolean updateTool(AdminVO.ToolItem item) {
		if (item.id() == null) {
			throw new IllegalArgumentException("Tool ID 不能为空");
		}
		LlmToolEntity entity = toToolEntity(item);
		sqlToolValidator.validateTool(entity);
		ensureToolNameUnique(entity);
		boolean updated = llmToolMapper.updateById(entity) > 0;
		if (!updated) {
			throw new IllegalStateException("Tool 不存在，无法更新");
		}
		refreshAfterCommit();
		return updated;
	}

	/**
	 * 删除动态 Tool 定义，并刷新当前 Tool 快照。
	 *
	 * @param id Tool ID
	 * @return 是否删除成功
	 */
	@Transactional(transactionManager = "erpTransactionManager")
	public boolean deleteTool(Long id) {
		if (id == null) {
			throw new IllegalArgumentException("Tool ID 不能为空");
		}
		boolean deleted = llmToolMapper.deleteById(id) > 0;
		if (!deleted) {
			throw new IllegalStateException("Tool 不存在，无法删除");
		}
		refreshAfterCommit();
		return deleted;
	}

	/**
	 * 手动刷新当前 Tool 快照。
	 *
	 * @return Tool 刷新结果
	 */
	public AdminVO.ToolRefreshResult refreshTools() {
		ToolSnapshot snapshot = toolRegistryService.refresh();
		return new AdminVO.ToolRefreshResult(snapshot.version(), snapshot.callbacks().size());
	}

	/**
	 * 查询当前租户的 Tool 调用流水。
	 *
	 * @param page     页码
	 * @param size     每页条数
	 * @param toolName Tool 名称
	 * @return Tool 调用流水列表
	 */
	public List<AdminVO.ToolCallLogItem> listCallLogs(Integer page, Integer size, String toolName) {
		int safePage = page == null ? 0 : Math.max(0, page);
		int safeSize = size == null ? 50 : Math.max(1, Math.min(size, 200));
		int offset = safePage * safeSize;
		String entCode = TenantContext.requireEntCode();
		QueryWrapper<ToolCallLogEntity> wrapper = new QueryWrapper<ToolCallLogEntity>()
			.eq("ent_code", entCode)
			.orderByDesc("created_at")
			.last("LIMIT " + offset + ", " + safeSize);
		if (toolName != null && !toolName.isBlank()) {
			wrapper.eq("tool_name", toolName.trim());
		}
		return toolCallLogMapper.selectList(wrapper).stream()
			.map(this::toToolCallLogItem)
			.toList();
	}

	/**
	 * 校验 Tool 名称在全局配置表中唯一。
	 *
	 * @param entity 动态 Tool 实体
	 */
	private void ensureToolNameUnique(LlmToolEntity entity) {
		LambdaQueryWrapper<LlmToolEntity> wrapper = new LambdaQueryWrapper<LlmToolEntity>()
			.eq(LlmToolEntity::getToolName, entity.getToolName());
		if (entity.getId() != null) {
			wrapper.ne(LlmToolEntity::getId, entity.getId());
		}
		Long count = llmToolMapper.selectCount(wrapper);
		if (count != null && count > 0) {
			throw new IllegalArgumentException("Tool 名称已存在");
		}
		// 动态 Tool 名称全局唯一，不能覆盖代码内置 Tool。
		var codeCallbacks = toolRegistryService.buildCodeToolCallbacks();
		if (codeCallbacks != null && codeCallbacks.stream()
			.anyMatch(callback -> entity.getToolName().equals(callback.getToolDefinition().name()))) {
			throw new IllegalArgumentException("Tool 名称已存在");
		}
	}

	/**
	 * 将实体转换为管理端 Tool VO。
	 *
	 * @param entity 动态 Tool 实体
	 * @return 管理端 Tool VO
	 */
	private AdminVO.ToolItem toToolItem(LlmToolEntity entity) {
		return new AdminVO.ToolItem(entity.getId(), entity.getToolName(), entity.getToolDesc(),
			entity.getInputSchema(), entity.getSqlTemplate(), entity.getTableAlias(), entity.getResultLimit(),
			entity.getStatus(), entity.getRemark(), toDateTimeString(entity.getCreatedAt()),
			toDateTimeString(entity.getUpdatedAt()));
	}

	/**
	 * 将管理端 Tool VO 转换为实体。
	 *
	 * @param item 管理端 Tool VO
	 * @return 动态 Tool 实体
	 */
	private LlmToolEntity toToolEntity(AdminVO.ToolItem item) {
		LlmToolEntity entity = new LlmToolEntity();
		entity.setId(item.id());
		entity.setToolName(item.toolName());
		entity.setToolDesc(item.toolDesc());
		entity.setInputSchema(item.inputSchema());
		entity.setSqlTemplate(item.sqlTemplate());
		entity.setTableAlias(item.tableAlias());
		entity.setResultLimit(item.resultLimit() == null ? 50 : item.resultLimit());
		entity.setStatus(item.status() == null || item.status().isBlank() ? "active" : item.status());
		entity.setRemark(item.remark());
		return entity;
	}

	/**
	 * 将实体转换为 Tool 调用流水 VO。
	 *
	 * @param entity Tool 调用流水实体
	 * @return Tool 调用流水 VO
	 */
	private AdminVO.ToolCallLogItem toToolCallLogItem(ToolCallLogEntity entity) {
		return new AdminVO.ToolCallLogItem(entity.getId(), entity.getTraceId(), entity.getConversationId(),
			entity.getMessageId(), entity.getEntCode(), entity.getUserId(), entity.getMode(), entity.getModel(),
			entity.getToolName(), entity.getToolType(), entity.getArgumentsJson(), entity.getResultCount(),
			entity.getDurationMs(), entity.getStatus(), entity.getErrorMessage(),
			toDateTimeString(entity.getCreatedAt()));
	}

	/**
	 * 将时间转换为前端展示字符串。
	 *
	 * @param dateTime 时间
	 * @return 时间字符串
	 */
	private String toDateTimeString(LocalDateTime dateTime) {
		return dateTime == null ? null : dateTime.toString().replace('T', ' ');
	}

	/**
	 * 在事务提交后刷新运行期 Tool 快照；无事务场景下立即刷新。
	 */
	private void refreshAfterCommit() {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			toolRegistryService.refresh();
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			/**
			 * 事务提交后刷新运行期 Tool 快照。
			 */
			@Override
			public void afterCommit() {
				toolRegistryService.refresh();
			}
		});
	}

}
