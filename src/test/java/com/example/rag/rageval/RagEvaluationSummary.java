package com.example.rag.rageval;

/**
 * RAG 评测聚合指标。
 *
 * @param caseCount             用例数
 * @param recallAt5             Recall@5
 * @param mrrAt5                MRR@5
 * @param emptyRetrievalRate    空召回率
 * @param noAnswerRefusalRate   无答案拒答率
 * @param citationValidityRate  引用有效率
 * @param keyFactHitRate        关键事实命中率
 * @param p50LatencyMs          P50 延迟
 * @param p95LatencyMs          P95 延迟
 * @param promptTokens          输入 Token 合计
 * @param completionTokens      输出 Token 合计
 * @param estimatedCostUsd      可空估算成本
 * @param crossBoundaryCount    越界召回合计
 * @param invalidCitationCount  非法引用合计
 * @param criticalFactViolationCount 关键错误结论合计
 * @param relevancyAverage      可空相关性均值
 * @param factCheckingAverage   可空事实一致性均值
 * @param recallTargetMet       Recall 目标是否达标
 * @param refusalTargetMet      拒答目标是否达标
 * @param citationTargetMet     引用目标是否达标
 */
public record RagEvaluationSummary(int caseCount, double recallAt5, double mrrAt5,
		double emptyRetrievalRate, double noAnswerRefusalRate, double citationValidityRate,
		double keyFactHitRate,
		long p50LatencyMs, long p95LatencyMs, int promptTokens, int completionTokens,
		Double estimatedCostUsd, int crossBoundaryCount, int invalidCitationCount,
		int criticalFactViolationCount, Double relevancyAverage, Double factCheckingAverage,
		boolean recallTargetMet,
		boolean refusalTargetMet, boolean citationTargetMet) {
}
