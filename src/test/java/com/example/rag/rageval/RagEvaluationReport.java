package com.example.rag.rageval;

import java.util.List;

/**
 * 可序列化的完整 RAG 评测报告。
 *
 * @param version     评测集版本
 * @param generatedAt 生成时间
 * @param summary     聚合指标
 * @param gate        硬门禁结果
 * @param cleanup     隔离数据清理结果
 * @param results     逐用例结果
 */
public record RagEvaluationReport(String version, String generatedAt,
		RagEvaluationSummary summary, RagEvaluationGateResult gate,
		RagEvaluationCleanupSummary cleanup,
		List<RagEvaluationCaseResult> results) {
}
