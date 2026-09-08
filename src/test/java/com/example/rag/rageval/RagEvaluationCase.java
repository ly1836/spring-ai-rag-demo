package com.example.rag.rageval;

import java.util.List;

/**
 * 单条版本化 RAG 评测用例。
 *
 * @param caseId                稳定用例 ID
 * @param question              问题
 * @param targetKnowledgeBase   目标知识库别名
 * @param expectedDocumentIds   期望文档 fixture ID
 * @param answerable            是否可回答
 * @param keyFacts              答案关键事实
 * @param forbiddenAnswerPhrases 禁止出现的关键错误结论
 * @param category              分类
 * @param tags                  标签
 * @param interferenceDocuments 隔离干扰文档范围
 */
public record RagEvaluationCase(String caseId, String question, String targetKnowledgeBase,
		List<String> expectedDocumentIds, boolean answerable, List<String> keyFacts,
		List<String> forbiddenAnswerPhrases, String category, List<String> tags,
		List<String> interferenceDocuments) {
}
