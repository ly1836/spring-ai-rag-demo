package com.example.rag.tool.trace;

/**
 * 单次问答内的 Tool 调用聚合记录。
 *
 * @param toolName     Tool 名称
 * @param toolType     Tool 来源类型
 * @param args         Tool 入参对象
 * @param status       调用状态
 * @param resultCount  返回结果条数
 * @param durationMs   调用耗时
 * @param errorMessage 错误信息
 */
public record ToolCallRecord(String toolName, String toolType, Object args, String status,
		Integer resultCount, Long durationMs, String errorMessage) {
}
