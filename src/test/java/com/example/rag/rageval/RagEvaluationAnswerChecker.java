package com.example.rag.rageval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 使用评测 fixture 中的显式短语执行确定性回答检查。
 */
public class RagEvaluationAnswerChecker {

	/** 紧邻禁止短语时可消除肯定语义的明确否定词。 */
	private static final List<String> IMMEDIATE_NEGATION_MARKERS = List.of(
		"并非", "不是", "不能", "不可", "禁止", "不允许", "无法", "不再", "未能");

	/** 表示当前资料不足以确认结论的谨慎语义。 */
	private static final List<String> UNCERTAINTY_MARKERS = List.of(
		"无法确认", "不能确定", "尚未确认", "无法判断", "不能判断");

	/** 标识禁止短语位于待确认问题中的疑问词。 */
	private static final List<String> QUESTION_MARKERS = List.of("是否", "能否");

	/** 表示回答已经从疑问范围转入实际结论的词语。 */
	private static final List<String> ASSERTION_MARKERS = List.of(
		"但", "不过", "然而", "实际上", "事实上", "却", "确实", "可以确定", "能够确定");

	/** 可明确肯定上一分句待确认结论的简短回答。 */
	private static final List<String> SHORT_AFFIRMATIVE_ANSWER_CLAUSES = List.of(
		"是", "是的", "对", "对的", "肯定", "肯定的", "可以", "可以的", "能", "能够",
		"答案可以", "答案是可以", "答案是可以的", "答案是肯定", "答案是肯定的",
		"结论可以", "结论是可以", "结论是可以的", "结论是肯定", "结论是肯定的");

	/** 可在冒号前承接上一分句待确认结论的回答标签。 */
	private static final List<String> ANSWER_LABEL_CLAUSES = List.of("答案", "结论");

	/** 保留分句级语义边界，避免前一分句的谨慎说明影响后续肯定结论。 */
	private static final String SENTENCE_BOUNDARY_REGEX =
		"(?:[。!！?？；;，,：:\\r\\n]+|\\.(?!\\d))";

	/**
	 * 统计关键事实命中并识别禁止出现的错误结论。
	 *
	 * @param answer                  模型回答
	 * @param keyFacts                应命中的关键事实
	 * @param forbiddenAnswerPhrases  禁止出现的关键错误结论
	 * @return 确定性检查结果
	 */
	public RagEvaluationAnswerCheck check(String answer, List<String> keyFacts,
			List<String> forbiddenAnswerPhrases) {
		String normalizedAnswer = normalize(answer);
		List<String> safeKeyFacts = keyFacts == null ? List.of() : keyFacts;
		int matchedKeyFactCount = 0;
		for (String keyFact : safeKeyFacts) {
			if (!normalize(keyFact).isEmpty() && normalizedAnswer.contains(normalize(keyFact))) {
				matchedKeyFactCount++;
			}
		}
		List<String> criticalFactViolations = new ArrayList<>();
		for (String forbiddenPhrase : forbiddenAnswerPhrases == null
				? List.<String>of() : forbiddenAnswerPhrases) {
			String normalizedPhrase = normalize(forbiddenPhrase);
			if (!normalizedPhrase.isEmpty()
					&& containsForbiddenAssertion(answer, normalizedPhrase)) {
				// 报告保留 fixture 中的原始短语，便于直接定位错误结论。
				criticalFactViolations.add(forbiddenPhrase);
			}
		}
		return new RagEvaluationAnswerCheck(matchedKeyFactCount, safeKeyFacts.size(),
			List.copyOf(criticalFactViolations));
	}

	/**
	 * 统一大小写并移除空白、标点和 Markdown 符号后比较短语。
	 *
	 * @param value 待规范化文本
	 * @return 可稳定比较的文本
	 */
	private String normalize(String value) {
		return value == null ? "" : value.toLowerCase(Locale.ROOT)
			.replaceAll("[\\s\\p{Punct}\\p{P}]+", "");
	}

	/**
	 * 判断回答是否以肯定语义出现禁止短语。
	 *
	 * @param answer           模型原始回答
	 * @param normalizedPhrase 已规范化禁止短语
	 * @return 存在未被否定或谨慎疑问修饰的禁止短语时返回 true
	 */
	private boolean containsForbiddenAssertion(String answer, String normalizedPhrase) {
		String safeAnswer = answer == null ? "" : answer;
		String pendingUncertainty = "";
		// 仅保留紧邻的待确认禁止结论，用于识别下一分句中的简短肯定回答。
		boolean pendingForbiddenQuestion = false;
		for (String sentence : safeAnswer.split(SENTENCE_BOUNDARY_REGEX)) {
			// 在分句内继续忽略空白、标点和 Markdown 符号，但不跨分句继承否定或疑问语义。
			String normalizedClause = normalize(sentence);
			// “答案：”或“结论：”允许继续等待冒号后的简短回答，其他分句不会扩大作用域。
			boolean continuingWithAnswerLabel = pendingForbiddenQuestion
				&& ANSWER_LABEL_CLAUSES.contains(normalizedClause);
			if (pendingForbiddenQuestion
					&& SHORT_AFFIRMATIVE_ANSWER_CLAUSES.contains(normalizedClause)) {
				// 简短肯定回答明确确认了上一分句中的禁止结论。
				return true;
			}
			String normalizedSentence = normalizedClause;
			int firstPhraseIndex = normalizedClause.indexOf(normalizedPhrase);
			// 上一分句仅以谨慎词收尾、当前分句继续提出疑问时，才延续不确定作用域。
			if (!pendingUncertainty.isEmpty() && firstPhraseIndex >= 0
					&& lastIndexOfAny(normalizedClause.substring(0, firstPhraseIndex),
						QUESTION_MARKERS) >= 0) {
				normalizedSentence = pendingUncertainty + normalizedClause;
			}
			int searchFrom = 0;
			int phraseIndex = normalizedSentence.indexOf(normalizedPhrase, searchFrom);
			// 只有未被否定且确实位于谨慎范围的禁止短语，才等待下一分句的简短回答。
			boolean uncertainForbiddenPhrase = false;
			while (phraseIndex >= 0) {
				boolean immediatelyNegated = hasImmediateNegation(normalizedSentence, phraseIndex);
				boolean insideUncertainScope = isInsideUncertainScope(normalizedSentence,
					normalizedPhrase, phraseIndex);
				if (!immediatelyNegated && !insideUncertainScope) {
					return true;
				}
				if (!immediatelyNegated && insideUncertainScope) {
					// 当前分句只提出待确认问题，下一分句可能用简短答案作出结论。
					uncertainForbiddenPhrase = true;
				}
				searchFrom = phraseIndex + normalizedPhrase.length();
				phraseIndex = normalizedSentence.indexOf(normalizedPhrase, searchFrom);
			}
			// 未出现回答标签或新的待确认禁止短语时，立即结束跨分句关联。
			pendingForbiddenQuestion = continuingWithAnswerLabel || uncertainForbiddenPhrase;
			pendingUncertainty = findTrailingUncertaintyMarker(normalizedClause);
		}
		return false;
	}

	/**
	 * 判断禁止短语前是否紧邻明确否定词。
	 *
	 * @param normalizedAnswer 已规范化回答
	 * @param phraseIndex      禁止短语起始位置
	 * @return 紧邻明确否定词时返回 true
	 */
	private boolean hasImmediateNegation(String normalizedAnswer, int phraseIndex) {
		String answerPrefix = normalizedAnswer.substring(0, phraseIndex);
		return IMMEDIATE_NEGATION_MARKERS.stream().anyMatch(answerPrefix::endsWith);
	}

	/**
	 * 判断禁止短语是否仅作为当前无法确认的对象出现。
	 *
	 * @param normalizedAnswer 已规范化的当前句子
	 * @param normalizedPhrase 已规范化禁止短语
	 * @param phraseIndex      禁止短语起始位置
	 * @return 位于尚未转入肯定结论的谨慎语义范围时返回 true
	 */
	private boolean isInsideUncertainScope(String normalizedAnswer, String normalizedPhrase,
			int phraseIndex) {
		String answerPrefix = normalizedAnswer.substring(0, phraseIndex);
		int uncertaintyIndex = lastIndexOfAny(answerPrefix, UNCERTAINTY_MARKERS);
		if (uncertaintyIndex < 0) {
			return false;
		}
		String uncertaintyScope = answerPrefix.substring(uncertaintyIndex);
		int questionIndex = lastIndexOfAny(uncertaintyScope, QUESTION_MARKERS);
		// 有疑问词时从疑问对象开始判断，没有疑问词时保留整个谨慎语义范围。
		String semanticScope = questionIndex < 0
			? uncertaintyScope : uncertaintyScope.substring(questionIndex);
		// 同一谨慎范围已出现过禁止短语时，当前重复项属于后续表述，不能继续豁免。
		return !semanticScope.contains(normalizedPhrase)
			&& ASSERTION_MARKERS.stream().noneMatch(semanticScope::contains);
	}

	/**
	 * 查找任一标记最后一次出现的位置。
	 *
	 * @param value   待检查文本
	 * @param markers 候选标记
	 * @return 最靠后的标记位置，不存在时返回 -1
	 */
	private int lastIndexOfAny(String value, List<String> markers) {
		return markers.stream()
			.mapToInt(value::lastIndexOf)
			.max()
			.orElse(-1);
	}

	/**
	 * 读取分句末尾可延续到后续疑问对象的谨慎词。
	 *
	 * @param value 已规范化分句
	 * @return 末尾谨慎词，不存在时返回空字符串
	 */
	private String findTrailingUncertaintyMarker(String value) {
		return UNCERTAINTY_MARKERS.stream()
			.filter(value::endsWith)
			.findFirst()
			.orElse("");
	}

}
