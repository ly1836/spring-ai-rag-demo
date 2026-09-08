package com.example.rag.rageval;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAG 纯函数指标和硬门禁测试。
 */
class RagEvaluationMetricsTest {

	/**
	 * 验证 Recall@5、MRR@5、拒答、引用、延迟和 Token 聚合。
	 */
	@Test
	public void shouldCalculateAllDeterministicMetrics() {
		List<RagEvaluationCaseResult> results = List.of(
			result("c1", true, List.of("a", "b"), List.of("x", "b"), false,
				1, 1, true, 0, 100, 10, 5, 3, 2, 0.001, 0.8F, 0.9F,
				1, 2, List.of()),
			result("c2", true, List.of("c"), List.of("c"), false,
				1, 2, false, 0, 200, 20, 10, 7, 4, null, null, null,
				1, 1, List.of()),
			result("c3", false, List.of(), List.of(), true,
				0, 0, true, 0, 300, 0, 0, 0, 0, 0.0, 1.0F, 1.0F,
				1, 1, List.of()));

		RagEvaluationSummary summary = new RagEvaluationMetrics().summarize(results);

		assertThat(summary.recallAt5()).isEqualTo(0.75);
		assertThat(summary.mrrAt5()).isEqualTo(0.75);
		assertThat(summary.emptyRetrievalRate()).isEqualTo(1.0 / 3.0);
		assertThat(summary.noAnswerRefusalRate()).isEqualTo(1.0);
		assertThat(summary.citationValidityRate()).isEqualTo(2.0 / 3.0);
		assertThat(summary.keyFactHitRate()).isEqualTo(0.75);
		assertThat(summary.p50LatencyMs()).isEqualTo(200);
		assertThat(summary.p95LatencyMs()).isEqualTo(300);
		assertThat(summary.promptTokens()).isEqualTo(40);
		assertThat(summary.completionTokens()).isEqualTo(21);
		assertThat(summary.estimatedCostUsd()).isNull();
		assertThat(summary.invalidCitationCount()).isEqualTo(1);
	}

	/**
	 * 验证越界、无效引用、证据不一致和基线下降都会触发硬失败。
	 */
	@Test
	public void shouldFailHardGateForSecurityAndRecallRegression() {
		RagEvaluationCaseResult result = result("c1", true, List.of("a"), List.of(), false,
			0, 1, false, 1, 100, 1, 1, 0, 0, 0.0, null, null,
			1, 2, List.of("仍可用于引用"));
		RagEvaluationSummary summary = new RagEvaluationMetrics().summarize(List.of(result));

		RagEvaluationGateResult gate = new RagEvaluationGate().evaluate(
			summary, new RagEvaluationBaseline("v1", 0.85), List.of(result), 1);

		assertThat(gate.passed()).isFalse();
		assertThat(gate.failures()).anyMatch(message -> message.contains("跨租户或跨知识库"));
		assertThat(gate.failures()).anyMatch(message -> message.contains("无效引用"));
		assertThat(gate.failures()).anyMatch(message -> message.contains("不属于本轮召回"));
		assertThat(gate.failures()).anyMatch(message -> message.contains("关键事实错误结论"));
		assertThat(gate.failures()).anyMatch(message -> message.contains("Recall@5"));
		// 回归门禁必须直接展示基线、当前值和下降差值。
		assertThat(gate.failures()).anyMatch(message -> message.contains(
			"baseline=0.8500, current=0.0000, 下降值=0.8500"));
	}

	/**
	 * 构造指标测试结果。
	 *
	 * @param caseId                  用例 ID
	 * @param answerable              是否可回答
	 * @param expected                期望文档
	 * @param retrieved               召回文档
	 * @param refused                 是否拒答
	 * @param validCitations          合法引用数
	 * @param totalCitations          总引用数
	 * @param citationsWithinEvidence 引用是否属于本轮证据
	 * @param crossBoundary           越界数
	 * @param latency                 延迟
	 * @param promptTokens            输入 Token
	 * @param completionTokens        输出 Token
	 * @param judgePromptTokens       Judge 输入 Token
	 * @param judgeCompletionTokens   Judge 输出 Token
	 * @param cost                    可空成本
	 * @param relevancy               可空相关性
	 * @param factChecking            可空事实一致性
	 * @param matchedKeyFacts         已命中的关键事实数
	 * @param totalKeyFacts           关键事实总数
	 * @param criticalFactViolations  命中的关键错误结论
	 * @return 用例结果
	 */
	private RagEvaluationCaseResult result(String caseId, boolean answerable,
			List<String> expected, List<String> retrieved, boolean refused,
			int validCitations, int totalCitations, boolean citationsWithinEvidence,
			int crossBoundary, long latency, int promptTokens, int completionTokens,
			int judgePromptTokens, int judgeCompletionTokens, Double cost,
			Float relevancy, Float factChecking, int matchedKeyFacts, int totalKeyFacts,
			List<String> criticalFactViolations) {
		return new RagEvaluationCaseResult(caseId, answerable, expected, retrieved, refused,
			validCitations, totalCitations, citationsWithinEvidence, crossBoundary,
			crossBoundary == 0 ? List.of() : List.of("跨知识库: documentId=doc-x"),
			matchedKeyFacts, totalKeyFacts, criticalFactViolations, latency,
			promptTokens, completionTokens, judgePromptTokens, judgeCompletionTokens,
			"test-provider", cost, cost, cost, relevancy, factChecking, null, "回答", null);
	}

	/**
	 * 验证评测未执行完整或单条用例失败时，报告门禁会明确失败。
	 */
	@Test
	public void shouldFailHardGateWhenEvaluationIsIncompleteOrCaseFailed() {
		// 构造一条已经记录失败原因的部分评测结果。
		RagEvaluationCaseResult failedResult = new RagEvaluationCaseResult("c1", true,
			List.of("doc-1"), List.of(), false, 0, 0, true, 0, List.of(),
			0, 1, List.of(), 100, 0, 0, 0, 0, "test-provider", null, null, null,
			null, null, null, "", "模型调用失败");
		RagEvaluationSummary summary = new RagEvaluationMetrics().summarize(
			List.of(failedResult));

		RagEvaluationGateResult gate = new RagEvaluationGate().evaluate(
			summary, null, List.of(failedResult), 2);

		assertThat(gate.passed()).isFalse();
		assertThat(gate.failures()).anyMatch(message -> message.contains("已完成 1/2"));
		assertThat(gate.failures()).anyMatch(message -> message.contains(
			"用例执行失败: caseId=c1，原因=模型调用失败"));
	}

}
