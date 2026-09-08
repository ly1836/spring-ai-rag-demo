package com.example.rag.rageval;

/**
 * 版本化 RAG 评测基线。
 *
 * @param version   评测集版本
 * @param recallAt5 基线 Recall@5
 */
public record RagEvaluationBaseline(String version, double recallAt5) {
}
