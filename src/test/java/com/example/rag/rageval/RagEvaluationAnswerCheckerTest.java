package com.example.rag.rageval;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAG 回答关键事实确定性检查测试。
 */
class RagEvaluationAnswerCheckerTest {

	/**
	 * 验证关键事实比较会忽略大小写、空白和 Markdown 标点。
	 */
	@Test
	public void shouldCountNormalizedKeyFactMatches() {
		RagEvaluationAnswerCheck result = new RagEvaluationAnswerChecker().check(
			"系统使用 **MySQL** 和 PgVector。", List.of("mysql", "PgVector", "不存在"), List.of());

		assertThat(result.matchedKeyFactCount()).isEqualTo(2);
		assertThat(result.totalKeyFactCount()).isEqualTo(3);
		assertThat(result.criticalFactViolations()).isEmpty();
	}

	/**
	 * 验证旧格式向量的错误肯定结论会被稳定识别。
	 */
	@Test
	public void shouldDetectForbiddenCriticalFactPhrase() {
		RagEvaluationAnswerCheck result = new RagEvaluationAnswerChecker().check(
			"旧格式向量仍可用于引用，但建议重新导入。", List.of("重新导入"),
			List.of("仍可用于引用", "仍可用于检索"));

		assertThat(result.matchedKeyFactCount()).isEqualTo(1);
		assertThat(result.criticalFactViolations()).containsExactly("仍可用于引用");
	}

	/**
	 * 验证明确否定禁止短语时不会误报关键错误结论。
	 */
	@Test
	public void shouldIgnoreExplicitlyNegatedForbiddenPhrase() {
		// “并非”紧邻错误结论，整句表达的是旧格式向量不可引用。
		RagEvaluationAnswerCheck result = new RagEvaluationAnswerChecker().check(
			"旧格式向量并非仍可用于引用，必须重新导入。", List.of("重新导入"),
			List.of("仍可用于引用"));

		assertThat(result.matchedKeyFactCount()).isEqualTo(1);
		assertThat(result.criticalFactViolations()).isEmpty();
	}

	/**
	 * 验证先否定后再次肯定同一禁止短语时仍会报告错误结论。
	 */
	@Test
	public void shouldDetectLaterAffirmativeForbiddenPhrase() {
		// 第一处由“并非”否定，第二处仍是需要拦截的肯定表述。
		RagEvaluationAnswerCheck result = new RagEvaluationAnswerChecker().check(
			"有人说并非仍可用于引用，但系统实际仍可用于引用。", List.of(),
			List.of("仍可用于引用"));

		assertThat(result.criticalFactViolations()).containsExactly("仍可用于引用");
	}

	/**
	 * 验证禁止短语位于无法确认的疑问对象中时不会误报。
	 */
	@Test
	public void shouldIgnoreForbiddenPhraseInsideUncertainQuestion() {
		// 回答没有确认旧格式向量可引用，只说明现有资料无法作出判断。
		RagEvaluationAnswerCheck result = new RagEvaluationAnswerChecker().check(
			"无法确认旧格式向量是否仍可用于引用。", List.of(),
			List.of("仍可用于引用"));

		assertThat(result.criticalFactViolations()).isEmpty();
	}

	/**
	 * 验证疑问对象后再次肯定同一禁止短语时仍会报告错误结论。
	 */
	@Test
	public void shouldDetectAffirmativePhraseAfterUncertainQuestion() {
		// 第一处属于无法确认的疑问，第二处“但实际”后的表述是明确结论。
		RagEvaluationAnswerCheck result = new RagEvaluationAnswerChecker().check(
			"无法确认旧格式向量是否仍可用于引用，但实际仍可用于引用。", List.of(),
			List.of("仍可用于引用"));

		assertThat(result.criticalFactViolations()).containsExactly("仍可用于引用");
	}

	/**
	 * 验证版本号中的点不会打断谨慎疑问范围。
	 */
	@Test
	public void shouldIgnoreUncertainQuestionContainingVersionNumber() {
		// 4.0.0 中的点属于版本号，不应被当作句子结束符。
		RagEvaluationAnswerCheck result = new RagEvaluationAnswerChecker().check(
			"无法确认 4.0.0 的旧格式向量是否仍可用于引用。", List.of(),
			List.of("仍可用于引用"));

		assertThat(result.criticalFactViolations()).isEmpty();
	}

	/**
	 * 验证不含疑问词的谨慎表述不会误报关键错误结论。
	 */
	@Test
	public void shouldIgnoreForbiddenPhraseInsideUncertainStatement() {
		// “不能确定”已经说明回答没有确认该错误结论。
		RagEvaluationAnswerCheck result = new RagEvaluationAnswerChecker().check(
			"不能确定旧格式向量仍可用于引用。", List.of(),
			List.of("仍可用于引用"));

		assertThat(result.criticalFactViolations()).isEmpty();
	}

	/**
	 * 验证谨慎表述后再次肯定同一禁止短语时仍会报告错误结论。
	 */
	@Test
	public void shouldDetectAffirmativePhraseAfterUncertainStatement() {
		// 第一处属于不能确定的对象，第二处“但实际”后的表述是明确结论。
		RagEvaluationAnswerCheck result = new RagEvaluationAnswerChecker().check(
			"不能确定旧格式向量仍可用于引用，但实际仍可用于引用。", List.of(),
			List.of("仍可用于引用"));

		assertThat(result.criticalFactViolations()).containsExactly("仍可用于引用");
	}

	/**
	 * 验证谨慎原因说明后的独立肯定分句仍会报告错误结论。
	 */
	@Test
	public void shouldDetectAffirmativePhraseAfterUncertainReasonClause() {
		// 逗号后的内容已是独立肯定结论，不能继承前一分句的“无法确认”。
		RagEvaluationAnswerCheck result = new RagEvaluationAnswerChecker().check(
			"无法确认原因，旧格式向量仍可用于引用。", List.of(),
			List.of("仍可用于引用"));

		assertThat(result.criticalFactViolations()).containsExactly("仍可用于引用");
	}

	/**
	 * 验证谨慎词单独成句后仍可延续到逗号或冒号后的疑问对象。
	 */
	@Test
	public void shouldPreserveUncertaintyForQuestionContinuation() {
		// 标点后的内容仍是待确认问题，不应被当成模型已经给出的肯定结论。
		RagEvaluationAnswerCheck commaResult = new RagEvaluationAnswerChecker().check(
			"无法确认，旧格式向量是否仍可用于引用。", List.of(),
			List.of("仍可用于引用"));
		RagEvaluationAnswerCheck colonResult = new RagEvaluationAnswerChecker().check(
			"无法确认：旧格式向量能否仍可用于引用。", List.of(),
			List.of("仍可用于引用"));

		assertThat(commaResult.criticalFactViolations()).isEmpty();
		assertThat(colonResult.criticalFactViolations()).isEmpty();
	}

	/**
	 * 验证谨慎疑问后的简短肯定回答仍会报告错误结论。
	 */
	@Test
	public void shouldDetectShortAffirmativeAnswerAfterUncertainQuestion() {
		// 后一句虽然没有重复禁止短语，但“可以”已经肯定了上一句的待确认结论。
		RagEvaluationAnswerCheck directResult = new RagEvaluationAnswerChecker().check(
			"无法确认：旧格式向量是否仍可用于引用。答案是可以的。", List.of(),
			List.of("仍可用于引用"));
		RagEvaluationAnswerCheck labeledResult = new RagEvaluationAnswerChecker().check(
			"无法确认旧格式向量是否仍可用于引用。结论：可以。", List.of(),
			List.of("仍可用于引用"));

		assertThat(directResult.criticalFactViolations()).containsExactly("仍可用于引用");
		assertThat(labeledResult.criticalFactViolations()).containsExactly("仍可用于引用");
	}

	/**
	 * 验证谨慎疑问后的简短否定回答不会误报错误结论。
	 */
	@Test
	public void shouldIgnoreShortNegativeAnswerAfterUncertainQuestion() {
		// “不可以”和“不能”都明确否定了上一句的待确认结论。
		RagEvaluationAnswerCheck directResult = new RagEvaluationAnswerChecker().check(
			"无法确认：旧格式向量是否仍可用于引用。答案是不可以的。", List.of(),
			List.of("仍可用于引用"));
		RagEvaluationAnswerCheck labeledResult = new RagEvaluationAnswerChecker().check(
			"无法确认旧格式向量是否仍可用于引用。结论：不能。", List.of(),
			List.of("仍可用于引用"));

		assertThat(directResult.criticalFactViolations()).isEmpty();
		assertThat(labeledResult.criticalFactViolations()).isEmpty();
	}

	/**
	 * 验证常见简短肯定表达都会确认上一分句中的错误结论。
	 */
	@Test
	public void shouldDetectCommonShortAffirmativeAnswers() {
		for (String affirmativeAnswer : List.of(
				"是的", "对", "对的", "答案是肯定的", "结论是肯定的")) {
			// 每个短答都直接肯定上一分句中的待确认结论。
			RagEvaluationAnswerCheck result = new RagEvaluationAnswerChecker().check(
				"无法确认旧格式向量是否仍可用于引用。" + affirmativeAnswer + "。",
				List.of(), List.of("仍可用于引用"));

			assertThat(result.criticalFactViolations())
				.as("简短肯定回答应触发关键错误结论：%s", affirmativeAnswer)
				.containsExactly("仍可用于引用");
		}
	}

	/**
	 * 验证常见简短否定表达不会被当成肯定结论。
	 */
	@Test
	public void shouldIgnoreCommonShortNegativeAnswers() {
		for (String negativeAnswer : List.of(
				"不是的", "不对", "答案是否定的", "结论是否定的")) {
			// 每个短答都明确否定上一分句中的待确认结论。
			RagEvaluationAnswerCheck result = new RagEvaluationAnswerChecker().check(
				"无法确认旧格式向量是否仍可用于引用。" + negativeAnswer + "。",
				List.of(), List.of("仍可用于引用"));

			assertThat(result.criticalFactViolations())
				.as("简短否定回答不应触发关键错误结论：%s", negativeAnswer)
				.isEmpty();
		}
	}

}
