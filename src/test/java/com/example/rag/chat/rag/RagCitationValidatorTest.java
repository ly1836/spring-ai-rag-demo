package com.example.rag.chat.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.example.rag.vo.ChatVO;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import org.springframework.ai.document.Document;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回答引用白名单校验测试。
 */
class RagCitationValidatorTest {

	/**
	 * 验证引用按首次出现排序、去重，并忽略代码字面量和伪造编号。
	 */
	@Test
	public void shouldValidateAndOrderCitationsByFirstAppearance() {
		RagCitationValidator validator = new RagCitationValidator(new ObjectMapper());
		List<Document> documents = List.of(document(1, "doc-1", "第一段", 0.91),
			document(2, "doc-2", "第二段", 0.88));

		// 转义文本、单双反引号代码和围栏代码都不能改变真实引用的首次出现顺序。
		List<ChatVO.CitationResponse> result = validator.validate(
			"转义 \\[2]、`[2]` 和 ``[2]`` 不算引用。\n```text\n[2]\n```\n"
				+ "~~~text\n[2]\n~~~\n先参考 [1]，再参考 [2]，重复 [1]，伪造 [99]。",
			"kb-1", documents);

		assertThat(result).extracting(ChatVO.CitationResponse::citationId).containsExactly(1, 2);
		assertThat(result).extracting(ChatVO.CitationResponse::documentId)
			.containsExactly("doc-1", "doc-2");
	}

	/**
	 * 验证回答无编号时不生成引用。
	 */
	@Test
	public void shouldReturnEmptyWhenAnswerHasNoCitationNumber() {
		RagCitationValidator validator = new RagCitationValidator(new ObjectMapper());

		assertThat(validator.validate("没有引用编号", "kb-1",
			List.of(document(1, "doc-1", "第一段", 0.91)))).isEmpty();
	}

	/**
	 * 验证引用数量不超过八条且摘要不超过五百字符。
	 */
	@Test
	public void shouldLimitCitationCountAndExcerptLength() {
		RagCitationValidator validator = new RagCitationValidator(new ObjectMapper());
		List<Document> documents = new ArrayList<>();
		StringBuilder answer = new StringBuilder();
		for (int index = 1; index <= 10; index++) {
			documents.add(document(index, "doc-" + index, "中".repeat(600), 0.9));
			answer.append("[").append(index).append("] ");
		}

		List<ChatVO.CitationResponse> result = validator.validate(answer.toString(), "kb-1", documents);

		assertThat(result).hasSize(8);
		assertThat(result).allMatch(item -> item.excerpt().codePointCount(0, item.excerpt().length()) <= 500);
	}

	/**
	 * 验证超大引用 JSON 会被拒绝而不是写入超限快照。
	 */
	@Test
	public void shouldRejectCitationThatExceedsJsonSizeLimit() throws Exception {
		RagCitationValidator validator = new RagCitationValidator(new ObjectMapper());
		Document oversized = Document.builder()
			.text("证据")
			.metadata(metadata(1, "文档".repeat(20000)))
			.build();

		List<ChatVO.CitationResponse> result = validator.validate("参考 [1]", "kb-1", List.of(oversized));

		assertThat(result).isEmpty();
	}

	/**
	 * 创建测试文档。
	 *
	 * @param citationId 引用编号
	 * @param documentId 文档 ID
	 * @param text       文本
	 * @param score      相似度
	 * @return 测试文档
	 */
	private Document document(int citationId, String documentId, String text, double score) {
		return Document.builder().text(text).metadata(metadata(citationId, documentId)).score(score).build();
	}

	/**
	 * 创建完整受管元数据。
	 *
	 * @param citationId 引用编号
	 * @param documentId 文档 ID
	 * @return 元数据映射
	 */
	private Map<String, Object> metadata(int citationId, String documentId) {
		return Map.of("citation_index", citationId, "document_id", documentId,
			"document_version", 2, "chunk_id", "chunk-" + citationId,
			"chunk_index", citationId - 1, "source", "manual.pdf");
	}

	/**
	 * 验证带尾随说明的同字符围栏不会提前关闭代码块。
	 */
	@Test
	public void shouldKeepFenceOpenWhenMarkerHasTrailingInfo() {
		RagCitationValidator validator = new RagCitationValidator(new ObjectMapper());
		List<Document> documents = List.of(document(1, "doc-1", "第一段", 0.91),
			document(2, "doc-2", "第二段", 0.88));

		// 代码块中的 ```java 和 [2] 都是字面量，只有关闭围栏后的 [1] 是真实引用。
		String answer = "```text\n代码开始\n```java\n[2]\n```\n正文引用 [1]";
		List<ChatVO.CitationResponse> result = validator.validate(answer, "kb-1", documents);

		assertThat(result).extracting(ChatVO.CitationResponse::citationId).containsExactly(1);
	}

	/**
	 * 验证代码行中间的围栏字符不会结束代码块。
	 */
	@Test
	public void shouldIgnoreMidLineFenceMarkerInsideFencedCode() {
		RagCitationValidator validator = new RagCitationValidator(new ObjectMapper());
		List<Document> documents = List.of(document(1, "doc-1", "第一段", 0.91),
			document(2, "doc-2", "第二段", 0.88));

		// 代码行末尾的三个反引号只是字面量，只有围栏关闭后的 [1] 是真实引用。
		String answer = "```text\n代码行末尾包含字面量 ```\n[2]\n```\n正文引用 [1]";
		List<ChatVO.CitationResponse> result = validator.validate(answer, "kb-1", documents);

		assertThat(result).extracting(ChatVO.CitationResponse::citationId).containsExactly(1);
	}

	/**
	 * 验证未闭合行内反引号按普通文本处理。
	 */
	@Test
	public void shouldTreatUnclosedInlineCodeMarkerAsPlainText() {
		RagCitationValidator validator = new RagCitationValidator(new ObjectMapper());

		// 未找到配对结束标记时，后续 [1] 仍应作为正文引用。
		List<ChatVO.CitationResponse> result = validator.validate(
			"未闭合反引号 ` 后的正文引用 [1]", "kb-1",
			List.of(document(1, "doc-1", "第一段", 0.91)));

		assertThat(result).extracting(ChatVO.CitationResponse::citationId).containsExactly(1);
	}

	/**
	 * 验证未闭合反引号不会使用后续围栏代码中的标记配对。
	 */
	@Test
	public void shouldNotCloseUnmatchedInlineCodeWithFenceContent() {
		RagCitationValidator validator = new RagCitationValidator(new ObjectMapper());

		// 围栏代码中的单个反引号不能与正文未闭合标记配对，正文 [1] 仍是真实引用。
		List<ChatVO.CitationResponse> result = validator.validate(
			"未闭合反引号 `，正文引用 [1]\n```text\n代码字面量 ` 不应参与配对\n```\n",
			"kb-1", List.of(document(1, "doc-1", "第一段", 0.91)));

		assertThat(result).extracting(ChatVO.CitationResponse::citationId).containsExactly(1);
	}

}
