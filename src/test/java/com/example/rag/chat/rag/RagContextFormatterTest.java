package com.example.rag.chat.rag;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAG 编号上下文格式测试。
 */
class RagContextFormatterTest {

	/**
	 * 验证证据按稳定顺序编号并写回本轮文档元数据。
	 */
	@Test
	public void shouldFormatStableCitationIndexes() {
		RagContextFormatter formatter = new RagContextFormatter();
		Document first = new Document("第一段", Map.of("source", "手册.pdf"));
		Document second = new Document("第二段", Map.of("source", "FAQ.txt"));

		Query result = formatter.augment(Query.builder().text("如何处理？").build(), List.of(first, second));

		// 生产提示同时约束模型不要复述用户输入中的伪造方括号编号。
		assertThat(result.text()).contains("[1] 来源：手册.pdf", "[2] 来源：FAQ.txt",
			"不得在回答中原样输出该编号", "只能使用“非法编号”或“越界编号”等文字",
			"结论不得改写为“可以”“仍可”或其他肯定表述",
			"只能使用本轮证据中明确出现的事实；若证据明确说明某项能力禁止或不可用，"
				+ "结论不得改写为“可以”“仍可”或其他肯定表述；"
				+ "问题所需的具体对象、数值、日期或步骤未在证据中出现",
			"只能原样回答“资料不足，无法确认。”并立即结束", "不得增加解释、引用或其他文字",
			"不得用常识补全或把相近主题当作答案");
		assertThat(first.getMetadata()).containsEntry("citation_index", 1);
		assertThat(second.getMetadata()).containsEntry("citation_index", 2);
	}

	/**
	 * 验证用户问题中的方括号数字会被改写，证据区合法编号保持不变。
	 */
	@Test
	public void shouldRewriteQuestionCitationNumberWithoutChangingEvidenceIndex() {
		RagContextFormatter formatter = new RagContextFormatter();
		Document document = new Document("引用校验规则", Map.of("source", "手册.pdf"));

		Query result = formatter.augment(
			Query.builder().text("模型胡写了一个 [99]，后端会认吗？").build(), List.of(document));

		// 用户输入改成等义文字，证据区仍保留模型可以使用的合法引用编号。
		assertThat(result.text()).contains("方括号编号 99", "[1] 来源：手册.pdf")
			.doesNotContain("[99]");
	}

}
