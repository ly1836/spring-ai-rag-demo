package com.example.rag.rageval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 将 RAG 评测结果同时写为 JSON 和 Markdown。
 */
public class RagEvaluationReportWriter {

	private final ObjectMapper objectMapper;

	/**
	 * 创建报告写入器。
	 *
	 * @param objectMapper JSON 序列化器
	 */
	public RagEvaluationReportWriter(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	/**
	 * 写入完整机器可读和人工可读报告。
	 *
	 * @param outputDirectory 输出目录
	 * @param version         评测集版本
	 * @param summary         聚合指标
	 * @param gate            硬门禁结果
	 * @param cleanup         隔离数据清理结果
	 * @param results         逐用例结果
	 * @return JSON 和 Markdown 文件路径
	 */
	public List<Path> write(Path outputDirectory, String version, RagEvaluationSummary summary,
			RagEvaluationGateResult gate, RagEvaluationCleanupSummary cleanup,
			List<RagEvaluationCaseResult> results) {
		try {
			Files.createDirectories(outputDirectory);
			RagEvaluationReport report = new RagEvaluationReport(version, Instant.now().toString(),
				summary, gate, cleanup, List.copyOf(results));
			Path jsonPath = outputDirectory.resolve("rag-evaluation.json");
			Path markdownPath = outputDirectory.resolve("rag-evaluation.md");
			this.objectMapper.writerWithDefaultPrettyPrinter().writeValue(jsonPath.toFile(), report);
			Files.writeString(markdownPath, toMarkdown(report), StandardCharsets.UTF_8);
			return List.of(jsonPath, markdownPath);
		}
		catch (IOException | JacksonException ex) {
			throw new IllegalStateException("RAG 评测报告写入失败: " + ex.getMessage(), ex);
		}
	}

	/**
	 * 生成包含指标、门禁和逐例结果的 Markdown。
	 *
	 * @param report 完整报告
	 * @return Markdown 文本
	 */
	private String toMarkdown(RagEvaluationReport report) {
		RagEvaluationSummary summary = report.summary();
		StringBuilder markdown = new StringBuilder();
		markdown.append("# RAG 评测报告\n\n")
			.append("- 评测集版本：").append(report.version()).append("\n")
			.append("- 生成时间：").append(report.generatedAt()).append("\n")
			.append("- 硬门禁：").append(report.gate().passed() ? "通过" : "失败").append("\n")
			.append("- 数据清理：").append(report.cleanup().passed() ? "通过" : "失败").append("\n\n")
			.append("## 聚合指标\n\n")
			.append("| 指标 | 结果 | 目标状态 |\n")
			.append("|---|---:|---|\n")
			.append(metricRow("Recall@5", percent(summary.recallAt5()), summary.recallTargetMet()))
			.append(metricRow("MRR@5", percent(summary.mrrAt5()), null))
			.append(metricRow("空召回率", percent(summary.emptyRetrievalRate()), null))
			.append(metricRow("无答案拒答率", percent(summary.noAnswerRefusalRate()), summary.refusalTargetMet()))
			.append(metricRow("引用有效率", percent(summary.citationValidityRate()), summary.citationTargetMet()))
			.append(metricRow("关键事实命中率", percent(summary.keyFactHitRate()), null))
			.append(metricRow("关键事实错误结论", String.valueOf(summary.criticalFactViolationCount()),
				summary.criticalFactViolationCount() == 0))
			.append(metricRow("P50 延迟", summary.p50LatencyMs() + " ms", null))
			.append(metricRow("P95 延迟", summary.p95LatencyMs() + " ms", null))
			.append(metricRow("输入 Token（回答 + Judge）", String.valueOf(summary.promptTokens()), null))
			.append(metricRow("输出 Token（回答 + Judge）", String.valueOf(summary.completionTokens()), null))
			.append(metricRow("估算成本", summary.estimatedCostUsd() == null ? "不可用"
				: String.format(Locale.ROOT, "$%.6f", summary.estimatedCostUsd()), null))
			.append(metricRow("相关性均值", nullableScore(summary.relevancyAverage()), null))
			.append(metricRow("事实一致性均值", nullableScore(summary.factCheckingAverage()), null))
			.append("\n## 硬门禁明细\n\n");
		if (report.gate().failures().isEmpty()) {
			markdown.append("- 无硬门禁失败。\n");
		}
		else {
			for (String failure : report.gate().failures()) {
				markdown.append("- ").append(escapeMarkdown(failure)).append("\n");
			}
		}
		markdown.append("\n## 数据清理\n\n");
		if (report.cleanup().failures().isEmpty()) {
			markdown.append("- 本轮随机租户关系数据和文档向量已完成清理。\n");
		}
		else {
			for (String failure : report.cleanup().failures()) {
				markdown.append("- ").append(escapeMarkdown(failure)).append("\n");
			}
		}
		markdown.append("\n## 逐用例结果\n\n")
			.append("| Case ID | Recall 文档 | 拒答 | 引用 | 关键事实 | 关键事实错误结论 | 越界明细 | 延迟 | Provider | 回答 Token | Judge Token | 回答成本 | Judge 成本 | 总成本 | Judge | 错误 |\n")
			.append("|---|---|---|---:|---:|---|---|---:|---|---:|---:|---:|---:|---:|---|---|\n");
		for (RagEvaluationCaseResult result : report.results()) {
			markdown.append("|").append(escapeMarkdown(result.caseId()))
				.append("|").append(escapeMarkdown(String.join(",", result.retrievedDocumentIds())))
				.append("|").append(result.refused() ? "是" : "否")
				.append("|").append(result.validCitationCount()).append("/").append(result.totalCitationCount())
				.append("|").append(result.matchedKeyFactCount()).append("/").append(result.totalKeyFactCount())
				.append("|").append(escapeMarkdown(String.join("；", result.criticalFactViolations())))
				.append("|").append(escapeMarkdown(String.join("；", result.boundaryViolations())))
				.append("|").append(result.latencyMs()).append(" ms")
				.append("|").append(escapeMarkdown(result.provider()))
				.append("|").append(result.promptTokens()).append("/").append(result.completionTokens())
				.append("|").append(result.judgePromptTokens()).append("/")
					.append(result.judgeCompletionTokens())
				.append("|").append(formatCost(result.answerEstimatedCostUsd()))
				.append("|").append(formatCost(result.judgeEstimatedCostUsd()))
				.append("|").append(formatCost(result.estimatedCostUsd()))
				.append("|").append(judgeSummary(result))
				.append("|").append(escapeMarkdown(result.error())).append("|\n");
		}
		return markdown.toString();
	}

	/**
	 * 构建指标表格行。
	 *
	 * @param name   指标名
	 * @param value  指标值
	 * @param target 可空目标状态
	 * @return Markdown 行
	 */
	private String metricRow(String name, String value, Boolean target) {
		String status = target == null ? "仅报告" : target ? "达标" : "未达标";
		return "|" + name + "|" + value + "|" + status + "|\n";
	}

	/**
	 * 格式化百分比。
	 *
	 * @param value 小数比率
	 * @return 百分比文本
	 */
	private String percent(double value) {
		return String.format(Locale.ROOT, "%.2f%%", value * 100.0);
	}

	/**
	 * 格式化可空 Judge 分数。
	 *
	 * @param value 可空分数
	 * @return 分数或不可用
	 */
	private String nullableScore(Double value) {
		return value == null ? "不可用" : String.format(Locale.ROOT, "%.4f", value);
	}

	/**
	 * 汇总单例 Judge 结果或失败原因。
	 *
	 * @param result 用例结果
	 * @return Judge 摘要
	 */
	private String judgeSummary(RagEvaluationCaseResult result) {
		if (result.judgeError() != null && !result.judgeError().isBlank()) {
			return escapeMarkdown(result.judgeError());
		}
		return "相关性=" + (result.relevancyScore() == null ? "-" : result.relevancyScore())
			+ ", 事实=" + (result.factCheckingScore() == null ? "-" : result.factCheckingScore());
	}

	/**
	 * 转义 Markdown 表格动态值。
	 *
	 * @param value 动态值
	 * @return 安全文本
	 */
	private String escapeMarkdown(String value) {
		return value == null ? "" : value.replace("|", "\\|").replace('\r', ' ').replace('\n', ' ');
	}

	/**
	 * 格式化单项或总估算成本。
	 *
	 * @param value 可空成本
	 * @return 美元成本或不可用
	 */
	private String formatCost(Double value) {
		return value == null ? "不可用" : String.format(Locale.ROOT, "$%.6f", value);
	}

}
