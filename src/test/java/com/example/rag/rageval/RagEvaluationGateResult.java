package com.example.rag.rageval;

import java.util.List;

/**
 * RAG 硬门禁结果。
 *
 * @param passed   是否通过
 * @param failures 失败原因
 */
public record RagEvaluationGateResult(boolean passed, List<String> failures) {
}
