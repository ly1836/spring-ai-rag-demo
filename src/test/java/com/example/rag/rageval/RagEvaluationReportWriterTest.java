package com.example.rag.rageval;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAG JSON 和 Markdown 报告测试。
 */
class RagEvaluationReportWriterTest {

	/**
	 * 验证报告包含逐例、目标、Provider、Token、成本不可用和硬门禁状态。
	 * @throws Exception 文件读取失败时抛出
	 */
	@Test
	public void shouldWriteCompleteJsonAndMarkdownReports() throws Exception {
		// 使用项目构建目录，避免 Windows 沙箱无法清理系统临时目录。
		Path tempDirectory = Path.of("target", "test-output", "rag-eval-" + UUID.randomUUID());
		RagEvaluationSummary summary = new RagEvaluationSummary(1, 0.9, 1.0, 0.0, 1.0, 1.0,
			0.5, 100, 100, 13, 7, null, 0, 0, 0, 0.8, 0.9, true, true, true);
		RagEvaluationGateResult gate = new RagEvaluationGateResult(true, List.of());
		RagEvaluationCleanupSummary cleanup = RagEvaluationCleanupSummary.successful();
		RagEvaluationCaseResult result = new RagEvaluationCaseResult("case|1", true,
			List.of("doc-1"), List.of("doc-1"), false, 1, 1, true, 0,
			List.of(), 1, 2, List.of(), 100, 10, 5, 3, 2, "deepseek", null, null, null,
			0.8F, 0.9F, null, "回答", null);

		List<Path> paths = new RagEvaluationReportWriter(new ObjectMapper()).write(
			tempDirectory, "v1", summary, gate, cleanup, List.of(result));
		String json = Files.readString(paths.get(0));
		String markdown = Files.readString(paths.get(1));

		assertThat(json).contains("\"recallAt5\" : 0.9", "\"provider\" : \"deepseek\"",
			"\"cleanup\"", "\"passed\" : true");
		assertThat(markdown).contains("硬门禁：通过", "数据清理：通过", "Recall@5", "输入 Token（回答 + Judge）",
			"输出 Token（回答 + Judge）", "关键事实命中率|50.00%", "关键事实错误结论|0|达标",
			"估算成本|不可用", "回答 Token", "Judge Token", "关键事实", "1/2",
			"回答成本", "Judge 成本", "总成本", "10/5", "3/2", "deepseek", "case\\|1",
			"相关性=0.8", "事实=0.9");
	}

	/**
	 * 验证部分评测报告在 JSON 和 Markdown 中都展示完整性失败原因。
	 * @throws Exception 文件读取失败时抛出
	 */
	@Test
	public void shouldWriteIncompleteEvaluationFailureToBothReports() throws Exception {
		// 使用独立输出目录，避免与完整报告测试相互覆盖。
		Path tempDirectory = Path.of("target", "test-output", "rag-eval-incomplete-"
			+ UUID.randomUUID());
		RagEvaluationCaseResult failedResult = new RagEvaluationCaseResult("case-1", true,
			List.of("doc-1"), List.of(), false, 0, 0, true, 0, List.of(),
			0, 1, List.of(), 100, 0, 0, 0, 0, "deepseek", null, null, null,
			null, null, null, "", "模型调用失败");
		RagEvaluationSummary summary = new RagEvaluationMetrics().summarize(
			List.of(failedResult));
		RagEvaluationGateResult gate = new RagEvaluationGate().evaluate(
			summary, null, List.of(failedResult), 2);

		List<Path> paths = new RagEvaluationReportWriter(new ObjectMapper()).write(
			tempDirectory, "v1", summary, gate,
			RagEvaluationCleanupSummary.successful(), List.of(failedResult));
		String json = Files.readString(paths.get(0));
		String markdown = Files.readString(paths.get(1));

		assertThat(json).contains("评测未完整执行: 已完成 1/2", "模型调用失败");
		assertThat(markdown).contains("硬门禁：失败", "评测未完整执行: 已完成 1/2",
			"用例执行失败: caseId=case-1，原因=模型调用失败");
	}

	/**
	 * 验证清理失败会进入硬门禁，并同时写入 JSON 和 Markdown 报告。
	 * @throws Exception 文件读取失败时抛出
	 */
	@Test
	public void shouldReportCleanupFailureAsHardGateFailure() throws Exception {
		Path tempDirectory = Path.of("target", "test-output", "rag-eval-cleanup-"
			+ UUID.randomUUID());
		RagEvaluationSummary summary = new RagEvaluationSummary(0, 0.0, 0.0, 0.0, 0.0, 1.0,
			0.0, 0, 0, 0, 0, null, 0, 0, 0, null, null, false, false, true);
		RagEvaluationCleanupSummary cleanup = new RagEvaluationCleanupSummary(false,
			List.of("删除向量失败: documentId=doc-1"));
		RagEvaluationGateResult gate = new RagEvaluationGate().evaluate(
			summary, null, List.of(), 0, cleanup);

		List<Path> paths = new RagEvaluationReportWriter(new ObjectMapper()).write(
			tempDirectory, "v1", summary, gate, cleanup, List.of());
		String json = Files.readString(paths.get(0));
		String markdown = Files.readString(paths.get(1));

		assertThat(gate.passed()).isFalse();
		assertThat(gate.failures()).containsExactly(
			"评测数据清理失败: 删除向量失败: documentId=doc-1");
		assertThat(json).contains("\"cleanup\"", "删除向量失败: documentId=doc-1");
		assertThat(markdown).contains("硬门禁：失败", "数据清理：失败",
			"## 数据清理", "删除向量失败: documentId=doc-1");
	}

}
