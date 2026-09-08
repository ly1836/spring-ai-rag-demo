package com.example.rag.rageval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAG 无答案拒答识别测试。
 */
class RagEvaluationRefusalClassifierTest {

	/**
	 * 验证明确信息不足且没有继续作答时会识别为拒答。
	 */
	@Test
	public void shouldAcceptExplicitChineseRefusal() {
		// 回答使用生产提示约定的固定拒答句，没有继续补充其他内容。
		String answer = "当前知识库资料不足，无法确认。";

		assertThat(RagEvaluationRefusalClassifier.isRefusal(answer)).isTrue();
	}

	/**
	 * 验证英文信息不足提示也会识别为拒答。
	 */
	@Test
	public void shouldAcceptExplicitEnglishRefusal() {
		// 英文回答明确说明现有证据不足。
		String answer = "The available evidence is insufficient to answer this question.";

		assertThat(RagEvaluationRefusalClassifier.isRefusal(answer)).isTrue();
	}

	/**
	 * 验证先说资料未提供却继续给出实际答案时不会误算为拒答。
	 */
	@Test
	public void shouldRejectRefusalFollowedByActualAnswer() {
		// 回答包含拒答标记，但随后仍给出未经证据支持的确定答案。
		String answer = "资料未提供，但实际答案是一年。";

		assertThat(RagEvaluationRefusalClassifier.isRefusal(answer)).isFalse();
	}

	/**
	 * 验证先说未包含却继续给出确定结论时不会误算为拒答。
	 */
	@Test
	public void shouldRejectRefusalFollowedByConclusion() {
		// 回答包含拒答标记，但随后仍用结论性表述继续作答。
		String answer = "当前资料未包含许可证价格，不过可以确定为 999 元。";

		assertThat(RagEvaluationRefusalClassifier.isRefusal(answer)).isFalse();
	}

	/**
	 * 验证没有拒答标记的正常答案不会识别为拒答。
	 */
	@Test
	public void shouldRejectAnswerWithoutRefusalMarker() {
		// 正常答案不包含任何资料不足或无法确认标记。
		String answer = "许可证价格为 999 元。";

		assertThat(RagEvaluationRefusalClassifier.isRefusal(answer)).isFalse();
	}

	/**
	 * 验证拒答后用普通转折词继续给出事实时不会误算为拒答。
	 */
	@Test
	public void shouldRejectRefusalFollowedByAdversativeFact() {
		// 回答没有使用“实际答案”等固定短语，但仍在“不过”后给出具体事实。
		String answer = "资料未提供，不过许可证有效期为一年。";

		assertThat(RagEvaluationRefusalClassifier.isRefusal(answer)).isFalse();
	}

	/**
	 * 验证英文拒答后用转折词继续给出事实时不会误算为拒答。
	 */
	@Test
	public void shouldRejectEnglishRefusalFollowedByAdversativeFact() {
		// 英文回答先声明证据不足，随后仍用 however 给出具体结论。
		String answer = "Not enough evidence; however, the license lasts one year.";

		assertThat(RagEvaluationRefusalClassifier.isRefusal(answer)).isFalse();
	}

	/**
	 * 验证英文单词内部包含 but 字母组合时不会误判为继续作答。
	 */
	@Test
	public void shouldAcceptEnglishRefusalContainingButLetters() {
		// contribution 内含 but 字母组合，但回答本身仍是明确拒答。
		String answer = "There is not enough contribution data to answer this question.";

		assertThat(RagEvaluationRefusalClassifier.isRefusal(answer)).isTrue();
	}

	/**
	 * 验证中文拒答在转折后只请求补充资料时仍会识别为拒答。
	 */
	@Test
	public void shouldAcceptChineseRefusalFollowedBySupplementRequest() {
		// 转折后的内容只说明下一步需要补充的资料，没有给出未经支持的结论。
		String answer = "资料不足，但请补充合同原文后再试。";

		assertThat(RagEvaluationRefusalClassifier.isRefusal(answer)).isTrue();
	}

	/**
	 * 验证英文拒答在转折后只请求补充资料时仍会识别为拒答。
	 */
	@Test
	public void shouldAcceptEnglishRefusalFollowedBySupplementRequest() {
		// 英文转折后的内容只请求提供资料，没有继续猜测答案。
		String answer = "Not enough evidence, but please provide the contract.";

		assertThat(RagEvaluationRefusalClassifier.isRefusal(answer)).isTrue();
	}

	/**
	 * 验证中文补充资料请求之后夹带确定事实时不会误算为拒答。
	 */
	@Test
	public void shouldRejectChineseSupplementRequestFollowedByFact() {
		// 回答先使用安全请求开头，但分号后仍给出未经证据支持的事实。
		String answer = "资料不足，但请补充合同；许可证有效期一年。";

		assertThat(RagEvaluationRefusalClassifier.isRefusal(answer)).isFalse();
	}

	/**
	 * 验证英文补充资料请求之后夹带确定事实时不会误算为拒答。
	 */
	@Test
	public void shouldRejectEnglishSupplementRequestFollowedByFact() {
		// 英文回答先请求补充资料，但后续仍给出未经支持的许可证期限。
		String answer = "Not enough evidence, but please provide the contract; the license lasts one year.";

		assertThat(RagEvaluationRefusalClassifier.isRefusal(answer)).isFalse();
	}

	/**
	 * 验证中文回答先给出事实再拒答时不会误算为拒答。
	 */
	@Test
	public void shouldRejectChineseFactBeforeRefusal() {
		// 回答已经给出未经证据支持的许可证期限，后续拒答不能抵消该事实。
		String answer = "许可证有效期一年。资料不足，无法确认。";

		assertThat(RagEvaluationRefusalClassifier.isRefusal(answer)).isFalse();
	}

	/**
	 * 验证英文回答先给出事实再拒答时不会误算为拒答。
	 */
	@Test
	public void shouldRejectEnglishFactBeforeRefusal() {
		// 英文回答已经给出未经支持的期限，后续证据不足提示不能将其视为正确拒答。
		String answer = "The license lasts one year. Not enough evidence to confirm.";

		assertThat(RagEvaluationRefusalClassifier.isRefusal(answer)).isFalse();
	}

	/**
	 * 验证中文补充资料请求在同一段夹带事实时不会误算为拒答。
	 */
	@Test
	public void shouldRejectChineseSupplementRequestWithAppendedFact() {
		// 安全请求开头之后通过连接词附加了未经支持的许可证期限。
		String answer = "资料不足，但请补充合同并记住许可证有效期一年。";

		assertThat(RagEvaluationRefusalClassifier.isRefusal(answer)).isFalse();
	}

	/**
	 * 验证英文补充资料请求在同一段夹带事实时不会误算为拒答。
	 */
	@Test
	public void shouldRejectEnglishSupplementRequestWithAppendedFact() {
		// 英文安全请求之后通过连接词附加了未经支持的许可证期限。
		String answer = "Not enough evidence, but please provide the contract and remember the license lasts one year.";

		assertThat(RagEvaluationRefusalClassifier.isRefusal(answer)).isFalse();
	}

	/**
	 * 验证中文补充资料请求换用其他连接词夹带事实时不会误算为拒答。
	 */
	@Test
	public void shouldRejectChineseSupplementRequestWithUnlistedConnector() {
		// 回答避开已有固定文字，但仍在补充资料请求之后给出了许可证期限。
		String answer = "资料不足，但请补充合同并了解许可证有效期是一年。";

		assertThat(RagEvaluationRefusalClassifier.isRefusal(answer)).isFalse();
	}

	/**
	 * 验证英文补充资料请求换用其他连接词夹带事实时不会误算为拒答。
	 */
	@Test
	public void shouldRejectEnglishSupplementRequestWithUnlistedConnector() {
		// 英文回答避开已有固定文字，但仍在补充资料请求之后给出了许可证期限。
		String answer = "Not enough evidence, but please provide the contract and be aware that the license lasts one year.";

		assertThat(RagEvaluationRefusalClassifier.isRefusal(answer)).isFalse();
	}

	/**
	 * 验证中文拒答详情之后另起一行给出事实时不会误算为拒答。
	 */
	@Test
	public void shouldRejectChineseFactOnFollowingLine() {
		// 换行后的许可证期限不再属于无法确认的对象说明。
		String answer = "资料不足，无法确认生产服务器公网 IP\n许可证有效期是一年。";

		assertThat(RagEvaluationRefusalClassifier.isRefusal(answer)).isFalse();
	}

	/**
	 * 验证中文拒答在“无法确认”后同句夹带事实时不会误算为拒答。
	 */
	@Test
	public void shouldRejectChineseFactImmediatelyAfterCannotConfirm() {
		// 回答以拒答开头，但紧接着给出了未经证据支持的许可证期限。
		String answer = "资料不足，无法确认但许可证有效期是一年。";

		assertThat(RagEvaluationRefusalClassifier.isRefusal(answer)).isFalse();
	}

	/**
	 * 验证英文拒答在“cannot confirm”后同句夹带事实时不会误算为拒答。
	 */
	@Test
	public void shouldRejectEnglishFactImmediatelyAfterCannotConfirm() {
		// 英文回答以拒答开头，但紧接着给出了未经证据支持的许可证期限。
		String answer = "Cannot confirm this question but the license lasts one year.";

		assertThat(RagEvaluationRefusalClassifier.isRefusal(answer)).isFalse();
	}

	/**
	 * 验证固定拒答句后仅说明知识库缺少被询问主题时仍会识别为拒答。
	 */
	@Test
	public void shouldAcceptRefusalFollowedByControlledMissingTopicExplanation() {
		// 后续内容只描述知识库缺少该主题，没有给出路线图功能结论。
		String answer = "资料不足，无法确认。当前知识库资料（产品手册、计费、运维、部署、安全等文档）"
			+ "中未包含“2027 年产品路线图”相关功能信息。";

		assertThat(RagEvaluationRefusalClassifier.isRefusal(answer)).isTrue();
	}

	/**
	 * 验证受控缺失说明后继续补充实际答案时不会误算为拒答。
	 */
	@Test
	public void shouldRejectControlledMissingTopicExplanationFollowedByFact() {
		// 完整句式之后又给出了未经支持的路线图功能，不能计入拒答率。
		String answer = "资料不足，无法确认。当前知识库资料中未包含“2027 年产品路线图”相关功能信息。"
			+ "路线图将支持 OCR。";

		assertThat(RagEvaluationRefusalClassifier.isRefusal(answer)).isFalse();
	}

	/**
	 * 验证明确拒答后仅概述现有资料范围与缺失内容时会识别为拒答。
	 */
	@Test
	public void shouldAcceptRefusalFollowedByEvidenceScopeExplanation() {
		// 后续段落只说明现有资料未涉及 OCR，没有回答系统是否支持 OCR。
		String answer = "根据当前知识库提供的资料，**无法确认系统是否支持 OCR 识别扫描版发票**。\n\n"
			+ "现有资料仅提及文档上传支持的格式以及文档导入处理流程，但均未涉及 OCR 相关内容。"
			+ "资料不足，无法确认。";

		assertThat(RagEvaluationRefusalClassifier.isRefusal(answer,
			"系统支持 OCR 识别扫描版发票吗？")).isTrue();
	}

	/**
	 * 验证资料范围说明后追加实际答案时不会误算为拒答。
	 */
	@Test
	public void shouldRejectEvidenceScopeExplanationFollowedByFact() {
		// 末尾给出了确定的 OCR 支持结论，不能计入拒答率。
		String answer = "根据当前知识库提供的资料，无法确认系统是否支持 OCR。"
			+ "现有资料仅提及文档上传，但均未涉及 OCR。系统实际支持 OCR。";

		assertThat(RagEvaluationRefusalClassifier.isRefusal(answer,
			"系统是否支持 OCR？")).isFalse();
	}

	/**
	 * 验证真实评测中以检索文档范围解释结尾的回答会识别为安全拒答。
	 */
	@Test
	public void shouldAcceptRealEvaluationEvidenceScopeRefusal() {
		// 回答只说明检索文档未涉及 OCR，并以资料不足收束。
		String answer = "根据当前知识库资料，**无法确认系统是否支持 OCR 识别扫描版发票**。"
			+ "检索到的文档中仅提及文档上传支持的格式以及文档导入处理流程，"
			+ "但均未涉及 OCR 相关内容。资料不足，无法确认。";

		assertThat(RagEvaluationRefusalClassifier.isRefusal(answer,
			"系统支持 OCR 识别扫描版发票吗？")).isTrue();
	}

	/**
	 * 验证真实评测中仅说明资料未包含被询问主题的回答会识别为安全拒答。
	 */
	@Test
	public void shouldAcceptRealEvaluationMissingTopicRefusal() {
		// 回答明确无法确认，并且后续只陈述资料缺少路线图信息。
		String answer = "根据当前知识库资料，无法确认 2027 年产品路线图的功能。"
			+ "资料中未包含任何关于 2027 年产品路线图的信息。";

		assertThat(RagEvaluationRefusalClassifier.isRefusal(answer,
			"2027 年产品路线图有哪些功能？")).isTrue();
	}

	/**
	 * 验证无法确认的对象说明中夹带实际结论时不会误算为拒答。
	 */
	@Test
	public void shouldRejectMissingTopicRefusalContainingActualAnswer() {
		// “但实际支持”已经给出确定答案，后续缺失说明不能把它变成安全拒答。
		String answer = "根据当前知识库资料，无法确认是否支持 OCR 但实际支持 OCR。"
			+ "资料中未包含任何关于 OCR 的信息。";

		assertThat(RagEvaluationRefusalClassifier.isRefusal(answer,
			"系统是否支持 OCR？")).isFalse();
	}

	/**
	 * 验证被询问主题包含“实际”一词时仍可识别为安全拒答。
	 */
	@Test
	public void shouldAcceptMissingTopicRefusalAboutActualEnvironment() {
		// “实际部署环境”是问题对象，不是转折后给出的确定结论。
		String answer = "根据当前知识库资料，无法确认实际部署环境是否支持 OCR。"
			+ "资料中未包含任何关于实际部署环境的信息。";

		assertThat(RagEvaluationRefusalClassifier.isRefusal(answer,
			"实际部署环境是否支持 OCR？")).isTrue();
	}

	/**
	 * 验证资料范围说明的首句夹带实际结论时不会误算为拒答。
	 */
	@Test
	public void shouldRejectEvidenceScopeRefusalContainingActualAnswer() {
		// 首句“但实际支持”已经给出确定答案，后续资料不足说明不能抵消该结论。
		String answer = "根据当前知识库资料，无法确认是否支持 OCR 但实际支持 OCR。"
			+ "现有资料仅提及文档上传，但均未涉及 OCR。资料不足，无法确认。";

		assertThat(RagEvaluationRefusalClassifier.isRefusal(answer,
			"系统是否支持 OCR？")).isFalse();
	}

	/**
	 * 验证资料缺失范围之后夹带实际结论时不会误算为拒答。
	 */
	@Test
	public void shouldRejectEvidenceScopeRefusalContainingTrailingActualAnswer() {
		// “均未涉及”后的“但实际支持”仍是未经证据支持的确定答案。
		String answer = "根据当前知识库资料，无法确认是否支持 OCR。"
			+ "现有资料仅提及文档上传，但均未涉及 OCR 但实际支持 OCR。资料不足，无法确认。";

		assertThat(RagEvaluationRefusalClassifier.isRefusal(answer,
			"系统是否支持 OCR？")).isFalse();
	}

	/**
	 * 验证资料范围说明的疑问对象后通过并列词给出事实时不会误算为拒答。
	 */
	@Test
	public void shouldRejectEvidenceScopeRefusalContainingConjunctiveAnswer() {
		// “并且系统支持 OCR”已经给出确定答案，后续资料不足说明不能抵消该结论。
		String answer = "根据当前知识库资料，无法确认是否支持 OCR 并且系统支持 OCR。"
			+ "现有资料仅提及文档上传，但均未涉及 OCR。资料不足，无法确认。";

		assertThat(RagEvaluationRefusalClassifier.isRefusal(answer,
			"系统是否支持 OCR？")).isFalse();
	}

	/**
	 * 验证资料范围说明不能通过未枚举的连接方式夹带肯定答案。
	 */
	@Test
	public void shouldRejectEvidenceScopeRefusalWithDifferentQuestionObject() {
		// 回答在原问题后又附加了肯定结论，不能按安全拒答统计。
		String answer = "根据当前知识库资料，无法确认系统是否支持 OCR 以及系统支持 OCR。"
			+ "现有资料仅提及文档上传，但均未涉及 OCR。资料不足，无法确认。";

		assertThat(RagEvaluationRefusalClassifier.isRefusal(answer,
			"系统是否支持 OCR？")).isFalse();
	}

}
