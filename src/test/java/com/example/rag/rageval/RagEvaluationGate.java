package com.example.rag.rageval;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG 安全隔离、证据一致性、关键错误结论和基线回归硬门禁。
 */
public class RagEvaluationGate {

	private static final double MAX_RECALL_REGRESSION = 0.05;

	/**
	 * 根据聚合指标和逐例证据判断硬门禁。
	 *
	 * @param summary  聚合指标
	 * @param baseline 版本基线
	 * @param results  逐例结果
	 * @param expectedCaseCount 评测集总用例数
	 * @return 门禁结果
	 */
	public RagEvaluationGateResult evaluate(RagEvaluationSummary summary,
			RagEvaluationBaseline baseline, List<RagEvaluationCaseResult> results,
			int expectedCaseCount) {
		return evaluate(summary, baseline, results, expectedCaseCount,
			RagEvaluationCleanupSummary.successful());
	}

	/**
	 * 根据聚合指标、逐例证据和清理结果判断硬门禁。
	 *
	 * @param summary           聚合指标
	 * @param baseline          版本基线
	 * @param results           逐例结果
	 * @param expectedCaseCount 评测集总用例数
	 * @param cleanup           隔离数据清理结果
	 * @return 门禁结果
	 */
	public RagEvaluationGateResult evaluate(RagEvaluationSummary summary,
			RagEvaluationBaseline baseline, List<RagEvaluationCaseResult> results,
			int expectedCaseCount, RagEvaluationCleanupSummary cleanup) {
		List<String> failures = new ArrayList<>();
		if (results.size() != expectedCaseCount) {
			failures.add("评测未完整执行: 已完成 " + results.size() + "/" + expectedCaseCount);
		}
		for (RagEvaluationCaseResult result : results) {
			if (result.error() != null && !result.error().isBlank()) {
				failures.add("用例执行失败: caseId=" + result.caseId()
					+ "，原因=" + result.error());
			}
		}
		if (summary.crossBoundaryCount() > 0) {
			failures.add("出现跨租户或跨知识库召回: " + summary.crossBoundaryCount());
		}
		for (RagEvaluationCaseResult result : results) {
			for (String violation : result.boundaryViolations()) {
				failures.add("越界明细: caseId=" + result.caseId() + "，" + violation);
			}
		}
		if (summary.invalidCitationCount() > 0) {
			failures.add("出现无效引用: " + summary.invalidCitationCount());
		}
		if (summary.criticalFactViolationCount() > 0) {
			failures.add("出现关键事实错误结论: " + summary.criticalFactViolationCount());
		}
		for (RagEvaluationCaseResult result : results) {
			if (!result.citationsWithinEvidence()) {
				failures.add("引用不属于本轮召回: " + result.caseId());
			}
			for (String violation : result.criticalFactViolations()) {
				failures.add("关键事实错误结论: caseId=" + result.caseId()
					+ "，命中禁止短语=" + violation);
			}
		}
		if (baseline != null && baseline.recallAt5() - summary.recallAt5()
				> MAX_RECALL_REGRESSION + 0.0000001) {
			// 显式保留下降差值，便于从评测报告直接判断回归幅度。
			double regression = baseline.recallAt5() - summary.recallAt5();
			failures.add(String.format(java.util.Locale.ROOT,
				"Recall@5 相对基线下降超过 5 个百分点: baseline=%.4f, current=%.4f, 下降值=%.4f",
				baseline.recallAt5(), summary.recallAt5(), regression));
		}
		if (cleanup != null && !cleanup.passed()) {
			for (String cleanupFailure : cleanup.failures()) {
				failures.add("评测数据清理失败: " + cleanupFailure);
			}
		}
		return new RagEvaluationGateResult(failures.isEmpty(), List.copyOf(failures));
	}

}
