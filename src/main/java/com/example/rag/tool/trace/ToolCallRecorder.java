package com.example.rag.tool.trace;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.alibaba.fastjson2.JSON;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

/**
 * 单次问答内的 Tool 调用聚合记录器。
 */
@Component
public class ToolCallRecorder {

	private static final Logger log = LoggerFactory.getLogger(ToolCallRecorder.class);

	/** traceId 到 Tool 调用记录列表的映射。 */
	private final ConcurrentMap<String, CopyOnWriteArrayList<ToolCallRecord>> records = new ConcurrentHashMap<>();

	/**
	 * 创建新的问答链路 ID。
	 *
	 * @return traceId
	 */
	public String createTraceId() {
		return UUID.randomUUID().toString();
	}

	/**
	 * 记录一次 Tool 调用。
	 *
	 * @param traceId Tool 调用所属链路 ID
	 * @param record  Tool 调用记录
	 */
	public void record(String traceId, ToolCallRecord record) {
		if (traceId == null || traceId.isBlank()) {
			return;
		}
		records.computeIfAbsent(traceId, key -> new CopyOnWriteArrayList<>()).add(record);
	}

	/**
	 * 获取指定链路的 Tool 调用次数。
	 *
	 * @param traceId Tool 调用所属链路 ID
	 * @return Tool 调用次数
	 */
	public int getToolCallCount(String traceId) {
		List<ToolCallRecord> list = records.get(traceId);
		return list == null ? 0 : list.size();
	}

	/**
	 * 获取指定链路的 Tool 调用记录 JSON。
	 *
	 * @param traceId Tool 调用所属链路 ID
	 * @return Tool 调用记录 JSON，没有调用时返回 null
	 */
	public String getToolCallsJson(String traceId) {
		List<ToolCallRecord> list = records.get(traceId);
		if (list == null || list.isEmpty()) {
			return null;
		}
		try {
			return JSON.toJSONString(list);
		}
		catch (Exception ex) {
			log.warn("序列化 Tool 调用聚合记录失败: {}", ex.getMessage());
			return null;
		}
	}

	/**
	 * 清理指定链路的聚合记录。
	 *
	 * @param traceId Tool 调用所属链路 ID
	 */
	public void clear(String traceId) {
		if (traceId != null) {
			records.remove(traceId);
		}
	}

	/**
	 * 将 Tool 参数 JSON 转换为聚合记录对象。
	 *
	 * @param toolInput Tool 参数 JSON
	 * @return 参数对象
	 */
	public Object parseArguments(String toolInput) {
		if (toolInput == null || toolInput.isBlank()) {
			return Map.of();
		}
		try {
			return JSON.parse(toolInput);
		}
		catch (Exception ex) {
			return toolInput;
		}
	}

}
