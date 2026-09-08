package com.example.rag.rageval;

import java.util.List;

/**
 * 真实 RAG 评测结束后的隔离数据清理结果。
 *
 * @param passed   是否确认完成全部清理
 * @param failures 未完成清理项及安全错误摘要
 */
public record RagEvaluationCleanupSummary(boolean passed, List<String> failures) {

	/**
	 * 固定失败列表，避免报告生成期间被外部修改。
	 */
	public RagEvaluationCleanupSummary {
		failures = failures == null ? List.of() : List.copyOf(failures);
	}

	/**
	 * 创建无失败的清理结果。
	 *
	 * @return 清理成功结果
	 */
	public static RagEvaluationCleanupSummary successful() {
		return new RagEvaluationCleanupSummary(true, List.of());
	}

}
