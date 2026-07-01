package com.example.rag.tool.trace;

import com.example.rag.dao.entity.ToolCallLogEntity;
import com.example.rag.dao.mapper.ToolCallLogMapper;
import com.example.rag.config.TenantContext;

import org.springframework.stereotype.Service;

/**
 * Tool 调用流水写入服务。
 */
@Service
public class ToolCallLogService {

	/** Tool 调用流水 Mapper。 */
	private final ToolCallLogMapper toolCallLogMapper;

	/**
	 * 创建 Tool 调用流水写入服务。
	 *
	 * @param toolCallLogMapper Tool 调用流水 Mapper
	 */
	public ToolCallLogService(ToolCallLogMapper toolCallLogMapper) {
		this.toolCallLogMapper = toolCallLogMapper;
	}

	/**
	 * 保存 Tool 调用流水。
	 *
	 * @param entity Tool 调用流水
	 */
	public void save(ToolCallLogEntity entity) {
		toolCallLogMapper.insert(entity);
	}

	/**
	 * 按当前租户将 Tool 调用流水关联到助手消息。
	 *
	 * @param traceId   问答链路 ID
	 * @param messageId 助手消息 ID
	 * @return 更新行数
	 */
	public int attachMessageId(String traceId, String messageId) {
		if (traceId == null || traceId.isBlank() || messageId == null || messageId.isBlank()) {
			return 0;
		}
		return toolCallLogMapper.updateMessageIdByTraceId(traceId, messageId, TenantContext.requireEntCode());
	}

}
