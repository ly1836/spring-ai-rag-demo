package com.example.rag.rageval;

import java.util.List;

/**
 * 单条 RAG 评测执行结果。
 *
 * @param caseId                  用例 ID
 * @param answerable              是否可回答
 * @param expectedDocumentIds     期望文档 ID
 * @param retrievedDocumentIds    前五个召回文档 ID
 * @param refused                 是否明确拒答
 * @param validCitationCount      合法引用数
 * @param totalCitationCount      模型回答中引用编号总数
 * @param citationsWithinEvidence 引用是否全部属于本轮证据
 * @param crossBoundaryCount      越界召回数
 * @param boundaryViolations      越界文档和边界类型明细
 * @param matchedKeyFactCount     已命中的关键事实数
 * @param totalKeyFactCount       关键事实总数
 * @param criticalFactViolations  命中的关键错误结论
 * @param latencyMs               端到端耗时
 * @param promptTokens            回答输入 Token
 * @param completionTokens        回答输出 Token
 * @param judgePromptTokens       Judge 输入 Token
 * @param judgeCompletionTokens   Judge 输出 Token
 * @param provider                Provider
 * @param answerEstimatedCostUsd  可空回答估算成本
 * @param judgeEstimatedCostUsd   可空 Judge 估算成本
 * @param estimatedCostUsd        可空估算成本
 * @param relevancyScore          可空相关性分数
 * @param factCheckingScore       可空事实一致性分数
 * @param judgeError              Judge 失败原因
 * @param answer                  回答文本
 * @param error                   用例执行失败原因
 */
public record RagEvaluationCaseResult(String caseId, boolean answerable,
		List<String> expectedDocumentIds, List<String> retrievedDocumentIds,
		boolean refused, int validCitationCount, int totalCitationCount,
		boolean citationsWithinEvidence, int crossBoundaryCount,
		List<String> boundaryViolations, int matchedKeyFactCount, int totalKeyFactCount,
		List<String> criticalFactViolations, long latencyMs,
		int promptTokens, int completionTokens, int judgePromptTokens,
		int judgeCompletionTokens, String provider, Double answerEstimatedCostUsd,
		Double judgeEstimatedCostUsd, Double estimatedCostUsd,
		Float relevancyScore, Float factCheckingScore, String judgeError, String answer,
		String error) {
}
