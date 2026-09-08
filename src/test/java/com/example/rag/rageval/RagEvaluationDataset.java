package com.example.rag.rageval;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 已校验的版本化 RAG 评测集。
 *
 * @param version       版本目录名
 * @param cases         评测用例
 * @param documents     fixture 文档 ID 到文件路径的映射
 * @param baseline      指标基线
 */
public record RagEvaluationDataset(String version, List<RagEvaluationCase> cases,
		Map<String, Path> documents, RagEvaluationBaseline baseline) {
}
