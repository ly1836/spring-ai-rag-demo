package com.example.rag.tool.trace;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.example.rag.chat.chart.capture.ToolResultRecorder;
import com.example.rag.config.TenantContext;
import com.example.rag.dao.entity.ToolCallLogEntity;
import com.alibaba.fastjson2.JSON;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * 带调用流水记录能力的 ToolCallback 包装器。
 */
public class LoggingToolCallback implements ToolCallback {

	private static final Logger log = LoggerFactory.getLogger(LoggingToolCallback.class);

	/** 被包装的原始 ToolCallback。 */
	private final ToolCallback delegate;

	/** Tool 来源类型：code / database。 */
	private final String toolType;

	/** Tool 调用聚合记录器。 */
	private final ToolCallRecorder recorder;

	/** Tool 调用流水服务。 */
	private final ToolCallLogService logService;

	/** 业务 Tool 结果记录器。 */
	private final ToolResultRecorder toolResultRecorder;

	/**
	 * 创建 ToolCallback 日志包装器。
	 *
	 * @param delegate     原始 ToolCallback
	 * @param toolType     Tool 来源类型
	 * @param recorder     Tool 调用聚合记录器
	 * @param logService   Tool 调用流水服务
	 * @param toolResultRecorder 业务 Tool 结果记录器
	 */
	public LoggingToolCallback(ToolCallback delegate, String toolType, ToolCallRecorder recorder,
			ToolCallLogService logService, ToolResultRecorder toolResultRecorder) {
		this.delegate = delegate;
		this.toolType = toolType;
		this.recorder = recorder;
		this.logService = logService;
		this.toolResultRecorder = toolResultRecorder;
	}

	/**
	 * 返回 Spring AI Tool 定义。
	 *
	 * @return Tool 定义
	 */
	@Override
	public ToolDefinition getToolDefinition() {
		return delegate.getToolDefinition();
	}

	/**
	 * 返回 Tool 元数据。
	 *
	 * @return Tool 元数据
	 */
	@Override
	public ToolMetadata getToolMetadata() {
		return delegate.getToolMetadata();
	}

	/**
	 * 执行 Tool 并记录调用流水。
	 *
	 * @param toolInput Tool 参数 JSON
	 * @return Tool 执行结果
	 */
	@Override
	public String call(String toolInput) {
		return call(toolInput, null);
	}

	/**
	 * 执行 Tool 并记录调用流水。
	 *
	 * @param toolInput   Tool 参数 JSON
	 * @param toolContext Tool 上下文
	 * @return Tool 执行结果
	 */
	@Override
	public String call(String toolInput, ToolContext toolContext) {
		Map<String, Object> context = readContext(toolContext);
		String traceId = contextValue(context, "traceId", UUID.randomUUID().toString());
		String toolName = delegate.getToolDefinition().name();
		long startTime = System.currentTimeMillis();
		String previousEntCode = TenantContext.getEntCode();
		String previousUserId = TenantContext.getUserId();
		applyTenantContext(context);
		try {
			String result = delegate.call(toolInput, toolContext);
			long durationMs = System.currentTimeMillis() - startTime;
			recordCall(traceId, context, toolName, toolInput, result, durationMs, "success", null);
			captureToolResult(traceId, context, toolName, result);
			return result;
		}
		catch (RuntimeException ex) {
			long durationMs = System.currentTimeMillis() - startTime;
			recordCall(traceId, context, toolName, toolInput, null, durationMs, "error", ex.getMessage());
			throw ex;
		}
		finally {
			restoreTenantContext(previousEntCode, previousUserId);
		}
	}

	/**
	 * 读取 Spring AI Tool 上下文。
	 *
	 * @param toolContext Tool 上下文
	 * @return 上下文 Map
	 */
	public Map<String, Object> readContext(ToolContext toolContext) {
		return toolContext == null || toolContext.getContext() == null ? Map.of() : toolContext.getContext();
	}

	/**
	 * 从上下文中读取字符串值。
	 *
	 * @param context      上下文 Map
	 * @param key          字段名
	 * @param defaultValue 默认值
	 * @return 字符串值
	 */
	public String contextValue(Map<String, Object> context, String key, String defaultValue) {
		Object value = context.get(key);
		if (value == null || value.toString().isBlank()) {
			return defaultValue;
		}
		return value.toString();
	}

	/**
	 * 从 Tool 上下文恢复租户 ThreadLocal。
	 *
	 * @param context Tool 上下文
	 */
	public void applyTenantContext(Map<String, Object> context) {
		String entCode = contextValue(context, "entCode", TenantContext.getEntCode());
		String userId = contextValue(context, "userId", TenantContext.getUserId());
		if (entCode != null && !entCode.isBlank()) {
			TenantContext.setEntCode(entCode);
		}
		if (userId != null && !userId.isBlank()) {
			TenantContext.setUserId(userId);
		}
	}

	/**
	 * 恢复调用前的租户 ThreadLocal。
	 *
	 * @param previousEntCode 调用前租户编码
	 * @param previousUserId  调用前用户 ID
	 */
	public void restoreTenantContext(String previousEntCode, String previousUserId) {
		TenantContext.clear();
		if (previousEntCode != null && !previousEntCode.isBlank()) {
			TenantContext.setEntCode(previousEntCode);
		}
		if (previousUserId != null && !previousUserId.isBlank()) {
			TenantContext.setUserId(previousUserId);
		}
	}

	/**
	 * 写入 Tool 调用流水并追加聚合记录。
	 *
	 * @param traceId      链路 ID
	 * @param context      Tool 上下文
	 * @param toolName     Tool 名称
	 * @param toolInput    Tool 参数 JSON
	 * @param result       Tool 执行结果
	 * @param durationMs   调用耗时
	 * @param status       调用状态
	 * @param errorMessage 错误信息
	 */
	public void recordCall(String traceId, Map<String, Object> context, String toolName, String toolInput,
			String result, long durationMs, String status, String errorMessage) {
		int resultCount = countResultRows(result);
		recorder.record(traceId, new ToolCallRecord(toolName, toolType, recorder.parseArguments(toolInput),
			status, resultCount, durationMs, errorMessage));
		try {
			logService.save(toLogEntity(traceId, context, toolName, toolInput, resultCount,
				durationMs, status, errorMessage));
		}
		catch (Exception ex) {
			log.warn("记录 Tool 调用流水失败: {}", ex.getMessage());
		}
	}

	/**
	 * 根据 Tool 返回 JSON 推断结果条数。
	 *
	 * @param result Tool 返回结果
	 * @return 结果条数
	 */
	public int countResultRows(String result) {
		if (result == null || result.isBlank()) {
			return 0;
		}
		try {
			Object parsed = JSON.parse(result);
			if (parsed instanceof List<?> list) {
				return list.size();
			}
			if (parsed instanceof Map<?, ?> map && map.containsKey("rows") && map.get("rows") instanceof List<?> rows) {
				return rows.size();
			}
			return 1;
		}
		catch (Exception ex) {
			return 1;
		}
	}

	/**
	 * 构建 Tool 调用流水实体。
	 *
	 * @param traceId      链路 ID
	 * @param context      Tool 上下文
	 * @param toolName     Tool 名称
	 * @param toolInput    Tool 参数 JSON
	 * @param resultCount  结果条数
	 * @param durationMs   调用耗时
	 * @param status       调用状态
	 * @param errorMessage 错误信息
	 * @return Tool 调用流水实体
	 */
	public ToolCallLogEntity toLogEntity(String traceId, Map<String, Object> context, String toolName,
			String toolInput, int resultCount, long durationMs, String status, String errorMessage) {
		ToolCallLogEntity entity = new ToolCallLogEntity();
		entity.setTraceId(traceId);
		entity.setConversationId(contextValue(context, "conversationId", null));
		entity.setEntCode(contextValue(context, "entCode", TenantContext.getEntCode()));
		entity.setUserId(contextValue(context, "userId", TenantContext.getUserId()));
		entity.setMode(contextValue(context, "mode", null));
		entity.setModel(contextValue(context, "model", null));
		entity.setToolName(toolName);
		entity.setToolType(toolType);
		entity.setArgumentsJson(toolInput);
		entity.setResultCount(resultCount);
		entity.setDurationMs(durationMs);
		entity.setStatus(status);
		entity.setErrorMessage(errorMessage);
		return entity;
	}

	/**
	 * 将成功业务 Tool 结果交给图表记录器，捕获失败不影响原 Tool 返回。
	 *
	 * @param traceId  问答链路 ID
	 * @param context  Tool 上下文
	 * @param toolName Tool 名称
	 * @param result   Tool 返回结果
	 */
	private void captureToolResult(String traceId, Map<String, Object> context, String toolName, String result) {
		try {
			toolResultRecorder.capture(traceId,
				contextValue(context, "entCode", TenantContext.getEntCode()),
				contextValue(context, "conversationId", null),
				toolName, toolType, result);
		}
		catch (Exception ex) {
			log.warn("图表结果捕获失败: traceId={}, toolName={}, error={}",
				traceId, toolName, ex.getMessage());
		}
	}

}
