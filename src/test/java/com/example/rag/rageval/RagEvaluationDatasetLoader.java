package com.example.rag.rageval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 版本化 RAG 评测集加载和结构校验器。
 */
public class RagEvaluationDatasetLoader {

	private static final Set<String> ALLOWED_CATEGORIES = Set.of(
		"single_document", "multi_document", "paraphrase", "no_answer", "isolation");

	private static final Map<String, Integer> MINIMUM_CATEGORY_COUNTS = Map.of(
		"single_document", 20,
		"multi_document", 10,
		"paraphrase", 10,
		"no_answer", 5,
		"isolation", 5);

	private final ObjectMapper objectMapper;

	/**
	 * 创建评测集加载器。
	 *
	 * @param objectMapper JSON 解析器
	 */
	public RagEvaluationDatasetLoader(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	/**
	 * 加载并完整校验指定版本目录。
	 *
	 * @param versionDirectory 包含 cases、baseline 和 documents 的目录
	 * @return 已校验评测集
	 */
	public RagEvaluationDataset load(Path versionDirectory) {
		try {
			Map<String, Path> documents = loadDocuments(versionDirectory.resolve("documents"));
			List<RagEvaluationCase> cases = Files.readAllLines(
				versionDirectory.resolve("cases.jsonl"), StandardCharsets.UTF_8).stream()
				.filter(line -> !line.isBlank())
				.map(this::parseCase)
				.toList();
			RagEvaluationBaseline baseline = this.objectMapper.readValue(
				versionDirectory.resolve("baseline.json").toFile(), RagEvaluationBaseline.class);
			validate(cases, documents.keySet(), baseline);
			return new RagEvaluationDataset(versionDirectory.getFileName().toString(),
				List.copyOf(cases), Map.copyOf(documents), baseline);
		}
		catch (IOException | JacksonException ex) {
			throw new IllegalArgumentException("RAG 评测集读取失败: " + ex.getMessage(), ex);
		}
	}

	/**
	 * 校验用例唯一性、字段、文档引用和分类下限。
	 *
	 * @param cases       用例
	 * @param documentIds fixture 文档 ID
	 * @param baseline    基线
	 */
	public void validate(List<RagEvaluationCase> cases, Set<String> documentIds,
			RagEvaluationBaseline baseline) {
		if (cases == null || cases.isEmpty()) {
			throw new IllegalArgumentException("RAG 评测集不能为空");
		}
		if (baseline == null || baseline.version() == null || baseline.version().isBlank()
				|| baseline.recallAt5() < 0.0 || baseline.recallAt5() > 1.0) {
			throw new IllegalArgumentException("RAG 评测基线非法");
		}
		Set<String> caseIds = new HashSet<>();
		Map<String, Integer> categoryCounts = new HashMap<>();
		for (RagEvaluationCase evaluationCase : cases) {
			validateCase(evaluationCase, documentIds);
			if (!caseIds.add(evaluationCase.caseId())) {
				throw new IllegalArgumentException("RAG 评测用例 ID 重复: " + evaluationCase.caseId());
			}
			categoryCounts.merge(evaluationCase.category(), 1, Integer::sum);
		}
		MINIMUM_CATEGORY_COUNTS.forEach((category, minimum) -> {
			int actual = categoryCounts.getOrDefault(category, 0);
			if (actual < minimum) {
				throw new IllegalArgumentException(
					"RAG 评测分类数量不足: " + category + "，要求至少 " + minimum + "，实际 " + actual);
			}
		});
	}

	/**
	 * 加载纯文本 fixture 文档。
	 *
	 * @param documentsDirectory 文档目录
	 * @return 文档 ID 到路径的映射
	 * @throws IOException 目录读取失败时抛出
	 */
	private Map<String, Path> loadDocuments(Path documentsDirectory) throws IOException {
		Map<String, Path> documents = new LinkedHashMap<>();
		try (Stream<Path> paths = Files.list(documentsDirectory)) {
			paths.filter(Files::isRegularFile)
				.filter(path -> path.getFileName().toString().endsWith(".txt"))
				.sorted()
				.forEach(path -> {
					String filename = path.getFileName().toString();
					String documentId = filename.substring(0, filename.length() - 4);
					documents.put(documentId, path);
				});
		}
		if (documents.isEmpty()) {
			throw new IllegalArgumentException("RAG 评测 fixture 文档不能为空");
		}
		return documents;
	}

	/**
	 * 解析单行 JSON 用例。
	 *
	 * @param line JSON 行
	 * @return 用例
	 */
	private RagEvaluationCase parseCase(String line) {
		try {
			return this.objectMapper.readValue(line, RagEvaluationCase.class);
		}
		catch (JacksonException ex) {
			throw new IllegalArgumentException("RAG 评测用例 JSON 非法: " + ex.getMessage(), ex);
		}
	}

	/**
	 * 校验单条用例结构。
	 *
	 * @param evaluationCase 用例
	 * @param documentIds    fixture 文档 ID
	 */
	private void validateCase(RagEvaluationCase evaluationCase, Set<String> documentIds) {
		if (evaluationCase == null || isBlank(evaluationCase.caseId())
				|| isBlank(evaluationCase.question()) || isBlank(evaluationCase.targetKnowledgeBase())) {
			throw new IllegalArgumentException("RAG 评测用例缺少必填字段");
		}
		if (!ALLOWED_CATEGORIES.contains(evaluationCase.category())) {
			throw new IllegalArgumentException(
				"RAG 评测分类非法: " + evaluationCase.caseId() + " -> " + evaluationCase.category());
		}
		if (evaluationCase.expectedDocumentIds() == null || evaluationCase.keyFacts() == null
				|| evaluationCase.keyFacts().isEmpty() || evaluationCase.tags() == null
				|| evaluationCase.tags().isEmpty()) {
			throw new IllegalArgumentException("RAG 评测用例数组字段不完整: " + evaluationCase.caseId());
		}
		if (evaluationCase.keyFacts().stream().anyMatch(this::isBlank)
				|| (evaluationCase.forbiddenAnswerPhrases() != null
					&& evaluationCase.forbiddenAnswerPhrases().stream().anyMatch(this::isBlank))) {
			// 关键事实短语必须可被确定性比较，禁止使用空白占位。
			throw new IllegalArgumentException("RAG 评测事实短语非法: " + evaluationCase.caseId());
		}
		if (evaluationCase.answerable() && evaluationCase.expectedDocumentIds().isEmpty()) {
			throw new IllegalArgumentException("可回答用例缺少期望文档: " + evaluationCase.caseId());
		}
		for (String documentId : evaluationCase.expectedDocumentIds()) {
			if (!documentIds.contains(documentId)) {
				throw new IllegalArgumentException(
					"RAG 评测用例引用不存在的文档: " + evaluationCase.caseId() + " -> " + documentId);
			}
		}
		if ("isolation".equals(evaluationCase.category())
				&& (evaluationCase.interferenceDocuments() == null
					|| evaluationCase.interferenceDocuments().isEmpty())) {
			throw new IllegalArgumentException("隔离用例缺少干扰文档: " + evaluationCase.caseId());
		}
	}

	/**
	 * 判断字符串是否为空。
	 *
	 * @param value 字符串
	 * @return 是否为空
	 */
	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

}
