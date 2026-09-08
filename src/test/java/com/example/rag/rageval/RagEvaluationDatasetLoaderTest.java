package com.example.rag.rageval;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 版本化 RAG 评测集加载校验测试。
 */
class RagEvaluationDatasetLoaderTest {

	/**
	 * 验证 v1 fixture 数量、分类和文档引用完整。
	 */
	@Test
	public void shouldLoadVersionOneDatasetWithRequiredCategoryCounts() {
		RagEvaluationDataset dataset = loader().load(Path.of("src/test/resources/rag-eval/v1"));

		assertThat(dataset.version()).isEqualTo("v1");
		assertThat(dataset.cases()).hasSize(50);
		assertThat(dataset.cases()).filteredOn(item -> "single_document".equals(item.category())).hasSize(20);
		assertThat(dataset.cases()).filteredOn(item -> "multi_document".equals(item.category())).hasSize(10);
		assertThat(dataset.cases()).filteredOn(item -> "paraphrase".equals(item.category())).hasSize(10);
		assertThat(dataset.cases()).filteredOn(item -> "no_answer".equals(item.category())).hasSize(5);
		assertThat(dataset.cases()).filteredOn(item -> "isolation".equals(item.category())).hasSize(5);
		assertThat(dataset.documents()).containsKeys("product-manual", "deployment-guide",
			"billing-guide", "security-guide", "operations-guide", "isolation-interference");
		assertThat(dataset.cases()).filteredOn(item -> "isolation-005".equals(item.caseId()))
			.singleElement().satisfies(item -> assertThat(item.forbiddenAnswerPhrases())
				.contains("仍可用于引用"));
	}

	/**
	 * 验证重复 caseId 会在模型调用前明确拒绝。
	 */
	@Test
	public void shouldRejectDuplicateCaseId() {
		RagEvaluationDataset dataset = loader().load(Path.of("src/test/resources/rag-eval/v1"));
		List<RagEvaluationCase> duplicated = new ArrayList<>(dataset.cases());
		duplicated.add(dataset.cases().get(0));

		assertThatThrownBy(() -> loader().validate(
			duplicated, dataset.documents().keySet(), dataset.baseline()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("ID 重复");
	}

	/**
	 * 验证缺失期望文档会返回包含用例和文档 ID 的错误。
	 */
	@Test
	public void shouldRejectMissingExpectedDocument() {
		RagEvaluationDataset dataset = loader().load(Path.of("src/test/resources/rag-eval/v1"));

		assertThatThrownBy(() -> loader().validate(
			dataset.cases(), java.util.Set.of("product-manual"), dataset.baseline()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("引用不存在的文档");
	}

	/**
	 * 验证非法分类会明确失败。
	 */
	@Test
	public void shouldRejectUnsupportedCategory() {
		RagEvaluationDataset dataset = loader().load(Path.of("src/test/resources/rag-eval/v1"));
		List<RagEvaluationCase> cases = new ArrayList<>(dataset.cases());
		RagEvaluationCase original = cases.get(0);
		cases.set(0, new RagEvaluationCase(original.caseId(), original.question(),
			original.targetKnowledgeBase(), original.expectedDocumentIds(), original.answerable(),
			original.keyFacts(), original.forbiddenAnswerPhrases(), "unknown", original.tags(),
			original.interferenceDocuments()));

		assertThatThrownBy(() -> loader().validate(cases, dataset.documents().keySet(), dataset.baseline()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("分类非法");
	}

	/**
	 * 验证任一分类低于规格数量时会明确失败。
	 */
	@Test
	public void shouldRejectInsufficientCategoryCount() {
		RagEvaluationDataset dataset = loader().load(Path.of("src/test/resources/rag-eval/v1"));
		List<RagEvaluationCase> cases = dataset.cases().stream()
			.filter(item -> !"single-020".equals(item.caseId()))
			.toList();

		assertThatThrownBy(() -> loader().validate(
			cases, dataset.documents().keySet(), dataset.baseline()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("分类数量不足")
			.hasMessageContaining("single_document");
	}

	/**
	 * 创建测试加载器。
	 *
	 * @return 加载器
	 */
	private RagEvaluationDatasetLoader loader() {
		return new RagEvaluationDatasetLoader(new ObjectMapper());
	}

}
