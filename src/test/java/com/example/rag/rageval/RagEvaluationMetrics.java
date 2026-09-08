package com.example.rag.rageval;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * RAG 评测纯函数指标计算器。
 */
public class RagEvaluationMetrics {

	private static final int TOP_K = 5;

	/**
	 * 聚合 Recall、MRR、拒答、引用、关键事实、延迟、Token 和成本指标。
	 *
	 * @param results 逐用例结果
	 * @return 聚合指标
	 */
	public RagEvaluationSummary summarize(List<RagEvaluationCaseResult> results) {
		if (results == null || results.isEmpty()) {
			throw new IllegalArgumentException("RAG 评测结果不能为空");
		}
		List<RagEvaluationCaseResult> answerable = results.stream()
			.filter(RagEvaluationCaseResult::answerable)
			.toList();
		List<RagEvaluationCaseResult> noAnswer = results.stream()
			.filter(result -> !result.answerable())
			.toList();
		double recallAt5 = answerable.stream().mapToDouble(this::recallAt5).average().orElse(0.0);
		double mrrAt5 = answerable.stream().mapToDouble(this::mrrAt5).average().orElse(0.0);
		double emptyRetrievalRate = ratio(results.stream()
			.filter(result -> result.retrievedDocumentIds() == null
				|| result.retrievedDocumentIds().isEmpty()).count(), results.size());
		double refusalRate = ratio(noAnswer.stream().filter(RagEvaluationCaseResult::refused).count(),
			noAnswer.size());
		int totalCitations = results.stream().mapToInt(RagEvaluationCaseResult::totalCitationCount).sum();
		int validCitations = results.stream().mapToInt(RagEvaluationCaseResult::validCitationCount).sum();
		double citationValidityRate = totalCitations == 0 ? 1.0 : ratio(validCitations, totalCitations);
		int totalKeyFacts = results.stream().mapToInt(RagEvaluationCaseResult::totalKeyFactCount).sum();
		int matchedKeyFacts = results.stream().mapToInt(RagEvaluationCaseResult::matchedKeyFactCount).sum();
		double keyFactHitRate = totalKeyFacts == 0 ? 1.0 : ratio(matchedKeyFacts, totalKeyFacts);
		List<Long> latencies = results.stream().map(RagEvaluationCaseResult::latencyMs).sorted().toList();
		int promptTokens = results.stream()
			.mapToInt(result -> result.promptTokens() + result.judgePromptTokens()).sum();
		int completionTokens = results.stream()
			.mapToInt(result -> result.completionTokens() + result.judgeCompletionTokens()).sum();
		Double estimatedCost = totalEstimatedCost(results, promptTokens + completionTokens);
		int crossBoundaryCount = results.stream().mapToInt(RagEvaluationCaseResult::crossBoundaryCount).sum();
		int invalidCitationCount = Math.max(totalCitations - validCitations, 0);
		int criticalFactViolationCount = results.stream()
			.mapToInt(result -> result.criticalFactViolations().size()).sum();
		Double relevancyAverage = averageNullable(results.stream()
			.map(RagEvaluationCaseResult::relevancyScore).toList());
		Double factCheckingAverage = averageNullable(results.stream()
			.map(RagEvaluationCaseResult::factCheckingScore).toList());
		return new RagEvaluationSummary(results.size(), recallAt5, mrrAt5, emptyRetrievalRate,
			refusalRate, citationValidityRate, keyFactHitRate,
			percentile(latencies, 0.50), percentile(latencies, 0.95),
			promptTokens, completionTokens, estimatedCost, crossBoundaryCount, invalidCitationCount,
			criticalFactViolationCount, relevancyAverage, factCheckingAverage, recallAt5 >= 0.85,
			refusalRate >= 0.90, citationValidityRate >= 1.0);
	}

	/**
	 * 计算单条用例 Recall@5。
	 *
	 * @param result 用例结果
	 * @return Recall@5
	 */
	public double recallAt5(RagEvaluationCaseResult result) {
		if (result.expectedDocumentIds() == null || result.expectedDocumentIds().isEmpty()) {
			return 0.0;
		}
		Set<String> expected = new HashSet<>(result.expectedDocumentIds());
		Set<String> retrieved = new HashSet<>(safeTopFive(result.retrievedDocumentIds()));
		retrieved.retainAll(expected);
		return ratio(retrieved.size(), expected.size());
	}

	/**
	 * 计算单条用例 MRR@5。
	 *
	 * @param result 用例结果
	 * @return MRR@5
	 */
	public double mrrAt5(RagEvaluationCaseResult result) {
		if (result.expectedDocumentIds() == null || result.expectedDocumentIds().isEmpty()) {
			return 0.0;
		}
		Set<String> expected = Set.copyOf(result.expectedDocumentIds());
		List<String> retrieved = safeTopFive(result.retrievedDocumentIds());
		for (int index = 0; index < retrieved.size(); index++) {
			if (expected.contains(retrieved.get(index))) {
				return 1.0 / (index + 1);
			}
		}
		return 0.0;
	}

	/**
	 * 返回最多前五个召回文档。
	 *
	 * @param documentIds 召回文档 ID
	 * @return 前五项
	 */
	private List<String> safeTopFive(List<String> documentIds) {
		if (documentIds == null || documentIds.isEmpty()) {
			return List.of();
		}
		return documentIds.subList(0, Math.min(TOP_K, documentIds.size()));
	}

	/**
	 * 计算安全比率。
	 *
	 * @param numerator   分子
	 * @param denominator 分母
	 * @return 比率，分母为零时返回零
	 */
	private double ratio(long numerator, long denominator) {
		return denominator == 0 ? 0.0 : (double) numerator / denominator;
	}

	/**
	 * 按 nearest-rank 规则计算延迟分位数。
	 *
	 * @param sortedValues 已排序值
	 * @param percentile   分位数
	 * @return 分位值
	 */
	private long percentile(List<Long> sortedValues, double percentile) {
		if (sortedValues.isEmpty()) {
			return 0L;
		}
		int index = Math.max(0, (int) Math.ceil(percentile * sortedValues.size()) - 1);
		return sortedValues.get(index);
	}

	/**
	 * 汇总可用成本；有 Token 但任一成本缺失时返回不可用。
	 *
	 * @param results     用例结果
	 * @param totalTokens 总 Token
	 * @return 成本或 null
	 */
	private Double totalEstimatedCost(List<RagEvaluationCaseResult> results, int totalTokens) {
		if (totalTokens > 0 && results.stream().anyMatch(result ->
			(result.promptTokens() > 0 || result.completionTokens() > 0
				|| result.judgePromptTokens() > 0 || result.judgeCompletionTokens() > 0)
				&& result.estimatedCostUsd() == null)) {
			return null;
		}
		return results.stream().map(RagEvaluationCaseResult::estimatedCostUsd)
			.filter(java.util.Objects::nonNull).mapToDouble(Double::doubleValue).sum();
	}

	/**
	 * 计算可空 Judge 分数均值。
	 *
	 * @param values 可空分数
	 * @return 均值或 null
	 */
	private Double averageNullable(List<Float> values) {
		List<Float> available = new ArrayList<>();
		for (Float value : values) {
			if (value != null) {
				available.add(value);
			}
		}
		return available.isEmpty() ? null
			: available.stream().mapToDouble(Float::doubleValue).average().orElse(0.0);
	}

}
