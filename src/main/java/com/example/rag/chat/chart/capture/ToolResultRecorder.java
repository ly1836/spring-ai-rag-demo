package com.example.rag.chat.chart.capture;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.alibaba.fastjson2.JSON;
import com.example.rag.chat.chart.model.BusinessToolResult;
import com.example.rag.chat.chart.model.TraceChartContext;
import com.example.rag.vo.ChartVO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

/**
 * 按 traceId 暂存本轮业务 Tool 结果和首个有效图表。
 */
@Component
public class ToolResultRecorder {

	private static final Logger log = LoggerFactory.getLogger(ToolResultRecorder.class);

	/** 每个 Tool 允许保留的最大数据行数。 */
	public static final int MAX_ROWS_PER_TOOL = 50;

	/** 每轮允许保留的最大业务 Tool 调用数。 */
	public static final int MAX_CALLS_PER_TRACE = 8;

	/** 单个字符串单元格的最大字符数。 */
	public static final int MAX_STRING_LENGTH = 500;

	/** Tool 结果允许的最大嵌套深度。 */
	public static final int MAX_NESTING_DEPTH = 8;

	/** 单次 Tool 原始结果允许的最大 UTF-8 字节数。 */
	public static final int MAX_RESULT_JSON_BYTES = 256 * 1024;

	/** 单个对象允许的最大字段数。 */
	public static final int MAX_FIELDS_PER_OBJECT = 64;

	/** 单个集合允许的最大元素数。 */
	public static final int MAX_COLLECTION_ITEMS = 200;

	/** 单次 Tool 结果允许的最大结构节点数。 */
	public static final int MAX_TOTAL_NODES = 5000;

	/** 上下文保守过期时间。 */
	public static final Duration CONTEXT_TTL = Duration.ofMinutes(10);

	/** 各问答链路的短生命周期图表上下文。 */
	private final ConcurrentHashMap<String, TraceChartContext> contexts = new ConcurrentHashMap<>();

	/**
	 * 捕获成功业务 Tool 的可图表化结果。
	 *
	 * @param traceId        问答链路 ID
	 * @param entCode        租户编码
	 * @param conversationId 会话 ID
	 * @param toolName       Tool 名称
	 * @param toolType       Tool 来源类型
	 * @param resultJson     Tool 原始返回 JSON
	 * @return 是否成功捕获
	 */
	public boolean capture(String traceId, String entCode, String conversationId,
			String toolName, String toolType, String resultJson) {
		cleanupExpired();
		if (!hasText(traceId) || !hasText(entCode) || !hasText(conversationId)
				|| !hasText(toolName) || !isBusinessToolType(toolType)) {
			log.warn("图表结果捕获已拒绝: traceId={}, toolName={}, 原因=链路上下文不完整", traceId, toolName);
			return false;
		}
		// 单次有界解析同时返回空结果状态，拒绝后不再解析原始 JSON。
		ParsedRows parsedRows = parseRows(resultJson);
		if (parsedRows.rows().isEmpty()) {
			String reason = parsedRows.explicitlyEmpty()
				? "业务 Tool 返回空结果" : "业务 Tool 结果不是受支持的结构化数据";
			log.warn("图表结果捕获已拒绝: traceId={}, toolName={}, 原因={}", traceId, toolName, reason);
			return false;
		}
		List<Map<String, Object>> rows = parsedRows.rows();
		final boolean[] accepted = {false};
		contexts.compute(traceId, (key, current) -> {
			if (current != null && !matches(current, entCode, conversationId)) {
				log.warn("图表结果捕获已拒绝: traceId={}, toolName={}, 原因=租户或会话不匹配", traceId, toolName);
				return current;
			}
			TraceChartContext base = current == null
				? new TraceChartContext(traceId, entCode, conversationId, List.of(), null, Instant.now())
				: current;
			if (base.results().size() >= MAX_CALLS_PER_TRACE) {
				log.warn("图表结果捕获已拒绝: traceId={}, toolName={}, 原因=Tool调用次数超限", traceId, toolName);
				return base;
			}
			List<BusinessToolResult> results = new ArrayList<>(base.results());
			results.add(new BusinessToolResult(results.size() + 1, toolName, toolType, rows, Instant.now()));
			accepted[0] = true;
			return new TraceChartContext(base.traceId(), base.entCode(), base.conversationId(),
				results, base.chart(), base.createdAt());
		});
		return accepted[0];
	}

	/**
	 * 读取当前链路的业务 Tool 结果。
	 *
	 * @param traceId        问答链路 ID
	 * @param entCode        租户编码
	 * @param conversationId 会话 ID
	 * @return 匹配链路边界的结果快照
	 */
	public List<BusinessToolResult> getResults(String traceId, String entCode, String conversationId) {
		cleanupExpired();
		TraceChartContext context = contexts.get(traceId);
		return context != null && matches(context, entCode, conversationId)
			? context.results() : List.of();
	}

	/**
	 * 记录本轮第一个通过完整校验的图表。
	 *
	 * @param traceId        问答链路 ID
	 * @param entCode        租户编码
	 * @param conversationId 会话 ID
	 * @param chart          已编译图表
	 * @return 是否首次成功写入
	 */
	public boolean acceptChart(String traceId, String entCode, String conversationId, ChartVO.ChartSpec chart) {
		if (chart == null) {
			return false;
		}
		final boolean[] accepted = {false};
		contexts.computeIfPresent(traceId, (key, current) -> {
			if (!matches(current, entCode, conversationId) || current.chart() != null) {
				return current;
			}
			accepted[0] = true;
			return new TraceChartContext(current.traceId(), current.entCode(), current.conversationId(),
				current.results(), chart, current.createdAt());
		});
		return accepted[0];
	}

	/**
	 * 读取本轮已接受图表。
	 *
	 * @param traceId        问答链路 ID
	 * @param entCode        租户编码
	 * @param conversationId 会话 ID
	 * @return 已接受图表，没有或边界不匹配时返回 null
	 */
	public ChartVO.ChartSpec getChart(String traceId, String entCode, String conversationId) {
		TraceChartContext context = contexts.get(traceId);
		return context != null && matches(context, entCode, conversationId) ? context.chart() : null;
	}

	/**
	 * 清理指定问答链路的原始结果与图表。
	 *
	 * @param traceId 问答链路 ID
	 */
	public void clear(String traceId) {
		if (traceId != null) {
			contexts.remove(traceId);
		}
	}

	/**
	 * 返回当前上下文数量，供资源释放测试使用。
	 *
	 * @return 上下文数量
	 */
	public int contextCount() {
		return contexts.size();
	}

	/**
	 * 清理保守过期的图表上下文。
	 */
	public void cleanupExpired() {
		Instant expiresBefore = Instant.now().minus(CONTEXT_TTL);
		contexts.entrySet().removeIf(entry -> entry.getValue().createdAt().isBefore(expiresBefore));
	}

	/**
	 * 将 Tool JSON 解析为安全数据行。
	 *
	 * @param resultJson Tool 原始返回 JSON
	 * @return 安全数据行与明确空结果状态
	 */
	private ParsedRows parseRows(String resultJson) {
		if (resultJson == null || resultJson.isBlank()) {
			return new ParsedRows(List.of(), false);
		}
		// 在 JSON 解析前限制原始字节数，避免超大输入先占用过多堆内存。
		if (resultJson.getBytes(StandardCharsets.UTF_8).length > MAX_RESULT_JSON_BYTES) {
			return new ParsedRows(List.of(), false);
		}
		try {
			Object parsed = JSON.parse(resultJson);
			List<?> rawRows;
			if (parsed instanceof List<?> list) {
				rawRows = list;
			}
			else if (parsed instanceof Map<?, ?> map && map.get("rows") instanceof List<?> rows) {
				rawRows = rows;
			}
			else if (parsed instanceof Map<?, ?> map) {
				rawRows = List.of(map);
			}
			else {
				return new ParsedRows(List.of(), false);
			}
			if (rawRows.isEmpty()) {
				return new ParsedRows(List.of(), true);
			}
			if (rawRows.size() > MAX_ROWS_PER_TOOL) {
				return new ParsedRows(List.of(), false);
			}
			List<Map<String, Object>> rows = new ArrayList<>();
			int[] nodeCount = {0};
			for (Object rawRow : rawRows) {
				if (!(rawRow instanceof Map<?, ?> map)) {
					return new ParsedRows(List.of(), false);
				}
				rows.add(sanitizeMap(map, 1, nodeCount));
			}
			return new ParsedRows(List.copyOf(rows), false);
		}
		catch (Exception ex) {
			return new ParsedRows(List.of(), false);
		}
	}

	/**
	 * 深度校验并复制单行数据。
	 *
	 * @param source 来源 Map
	 * @param depth  当前嵌套深度
	 * @param nodeCount 当前已读取节点数
	 * @return 安全复制后的数据
	 */
	private Map<String, Object> sanitizeMap(Map<?, ?> source, int depth, int[] nodeCount) {
		checkDepth(depth);
		consumeNode(nodeCount);
		if (source.size() > MAX_FIELDS_PER_OBJECT) {
			throw new IllegalArgumentException("Tool结果对象字段数量超限");
		}
		Map<String, Object> target = new LinkedHashMap<>();
		for (Map.Entry<?, ?> entry : source.entrySet()) {
			if (!(entry.getKey() instanceof String key) || key.isBlank()) {
				throw new IllegalArgumentException("字段名非法");
			}
			target.put(key, sanitizeValue(entry.getValue(), depth + 1, nodeCount));
		}
		return Collections.unmodifiableMap(target);
	}

	/**
	 * 深度校验并复制单个单元格。
	 *
	 * @param value 来源值
	 * @param depth 当前嵌套深度
	 * @param nodeCount 当前已读取节点数
	 * @return 安全复制后的值
	 */
	private Object sanitizeValue(Object value, int depth, int[] nodeCount) {
		checkDepth(depth);
		if (value instanceof String text) {
			consumeNode(nodeCount);
			if (text.length() > MAX_STRING_LENGTH) {
				throw new IllegalArgumentException("字符串单元格超限");
			}
			return text;
		}
		if (value instanceof Map<?, ?> map) {
			return sanitizeMap(map, depth, nodeCount);
		}
		if (value instanceof List<?> list) {
			consumeNode(nodeCount);
			if (list.size() > MAX_COLLECTION_ITEMS) {
				throw new IllegalArgumentException("Tool结果集合元素数量超限");
			}
			return list.stream().map(item -> sanitizeValue(item, depth + 1, nodeCount)).toList();
		}
		consumeNode(nodeCount);
		return value;
	}

	/**
	 * 校验嵌套深度。
	 *
	 * @param depth 当前深度
	 */
	private void checkDepth(int depth) {
		if (depth > MAX_NESTING_DEPTH) {
			throw new IllegalArgumentException("Tool结果嵌套深度超限");
		}
	}

	/**
	 * 判断 Tool 来源是否属于业务 Tool。
	 *
	 * @param toolType Tool 来源
	 * @return 是否属于业务 Tool
	 */
	private boolean isBusinessToolType(String toolType) {
		return "code".equals(toolType) || "database".equals(toolType);
	}

	/**
	 * 判断上下文边界是否一致。
	 *
	 * @param context        图表上下文
	 * @param entCode        租户编码
	 * @param conversationId 会话 ID
	 * @return 是否一致
	 */
	private boolean matches(TraceChartContext context, String entCode, String conversationId) {
		return context.entCode().equals(entCode) && context.conversationId().equals(conversationId);
	}

	/**
	 * 判断字符串是否包含有效文本。
	 *
	 * @param value 待检查字符串
	 * @return 是否包含有效文本
	 */
	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	/**
	 * 累加并校验单次 Tool 结果结构节点数量。
	 *
	 * @param nodeCount 当前节点计数器
	 */
	private void consumeNode(int[] nodeCount) {
		nodeCount[0]++;
		if (nodeCount[0] > MAX_TOTAL_NODES) {
			throw new IllegalArgumentException("Tool结果结构节点数量超限");
		}
	}

	/**
	 * 单次 Tool JSON 解析结果，携带安全行和明确空数据状态。
	 *
	 * @param rows            已校验的数据行
	 * @param explicitlyEmpty 原始 JSON 是否明确表示空数据集
	 */
	private record ParsedRows(List<Map<String, Object>> rows, boolean explicitlyEmpty) {
	}

}
