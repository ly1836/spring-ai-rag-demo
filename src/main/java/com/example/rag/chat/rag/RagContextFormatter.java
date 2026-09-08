package com.example.rag.chat.rag;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.generation.augmentation.QueryAugmenter;
import org.springframework.stereotype.Component;

/**
 * 将合格检索分片格式化为带稳定编号的知识上下文。
 */
@Component
public class RagContextFormatter implements QueryAugmenter {

	/** 用户问题中的方括号数字，进入模型前改写为等义文字以免被误当作知识引用。 */
	private static final Pattern QUESTION_CITATION_PATTERN = Pattern.compile("\\[(\\d+)]");

	/**
	 * 为原问题追加编号知识上下文和引用约束。
	 *
	 * @param query     原始查询
	 * @param documents 合格检索分片
	 * @return 增强查询
	 */
	@Override
	public Query augment(Query query, List<Document> documents) {
		StringBuilder context = new StringBuilder();
		for (int index = 0; index < documents.size(); index++) {
			Document document = documents.get(index);
			int citationIndex = index + 1;
			// 引用序号写回同一文档元数据，供响应阶段白名单校验复用。
			document.getMetadata().put("citation_index", citationIndex);
			context.append("[").append(citationIndex).append("] 来源：")
				.append(safeSource(document)).append("\n")
				.append(document.getText() == null ? "" : document.getText())
				.append("\n\n");
		}
		// 用户原问题中的方括号数字不属于本轮证据引用，先转成等义文字。
		String augmented = sanitizeQuestionCitationNumbers(query.text())
			+ "\n\n以下是本轮知识库证据。引用这些知识时必须使用对应 [n] 编号，"
			// 避免把用户问题中的伪造编号原样带入回答并被误识别为知识引用。
			+ "不得编造或复述不存在的方括号编号；即使用户问题中包含不存在的编号，"
			+ "也不得在回答中原样输出该编号，说明时只能使用“非法编号”或“越界编号”等文字；"
			+ "ERP Tool 的本轮实时结果仍可直接用于回答，但不得伪造知识引用；"
			+ "只能使用本轮证据中明确出现的事实；"
			// 证据明确禁止的能力不得被模型改写为仍可使用。
			+ "若证据明确说明某项能力禁止或不可用，结论不得改写为“可以”“仍可”或其他肯定表述；"
			+ "问题所需的具体对象、数值、日期或步骤未在证据中出现，且本轮 ERP Tool 也未提供时，"
			+ "只能原样回答“资料不足，无法确认。”并立即结束，"
			+ "不得增加解释、引用或其他文字；不得用常识补全或把相近主题当作答案。\n\n"
			+ context;
		return Query.builder()
			.text(augmented)
			.history(query.history())
			.context(query.context())
			.build();
	}

	/**
	 * 读取安全的来源展示名称。
	 *
	 * @param document 文档分片
	 * @return 来源名称
	 */
	private String safeSource(Document document) {
		Object source = document.getMetadata().get("source");
		return source == null ? "未知来源" : String.valueOf(source);
	}

	/**
	 * 将用户问题中的方括号数字改写为不会触发引用识别的等义文字。
	 *
	 * @param question 用户原始问题
	 * @return 已安全改写的问题
	 */
	private String sanitizeQuestionCitationNumbers(String question) {
		if (question == null || question.isEmpty()) {
			return "";
		}
		return QUESTION_CITATION_PATTERN.matcher(question).replaceAll("方括号编号 $1");
	}

}
