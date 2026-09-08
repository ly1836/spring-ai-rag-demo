package com.example.rag.rageval;

import java.util.List;

/**
 * 单条回答的确定性关键事实检查结果。
 *
 * @param matchedKeyFactCount     已命中的关键事实数
 * @param totalKeyFactCount       关键事实总数
 * @param criticalFactViolations  命中的关键错误结论
 */
public record RagEvaluationAnswerCheck(int matchedKeyFactCount, int totalKeyFactCount,
		List<String> criticalFactViolations) {
}
