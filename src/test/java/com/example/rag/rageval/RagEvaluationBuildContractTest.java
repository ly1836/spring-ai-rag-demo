package com.example.rag.rageval;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAG 真实评测与普通离线构建隔离契约测试。
 */
class RagEvaluationBuildContractTest {

	/**
	 * 验证只有显式 rag-eval Profile 才由 Failsafe 运行真实评测 IT。
	 *
	 * @throws Exception 文件读取失败时抛出
	 */
	@Test
	public void shouldKeepRealEvaluationOutsideDefaultSurefireLifecycle() throws Exception {
		String pom = Files.readString(Path.of("pom.xml"));

		assertThat(pom)
			.contains("<id>rag-eval</id>")
			.contains("maven-failsafe-plugin")
			.contains("<include>**/RagEvaluationIT.java</include>")
			.contains("<rag.eval.enabled>true</rag.eval.enabled>")
			.doesNotContain("maven-surefire-plugin");
	}

	/**
	 * 验证真实评测通过构造器注入依赖，避免测试上下文隐藏字段注入问题。
	 *
	 * @throws Exception 文件读取失败时抛出
	 */
	@Test
	public void shouldUseConstructorInjectionInRealEvaluationRunner() throws Exception {
		String source = Files.readString(
				Path.of("src/test/java/com/example/rag/rageval/RagEvaluationIT.java"));

		assertThat(source)
			.contains("public RagEvaluationIT(")
			.contains("private final KnowledgeDocumentIngestionService ingestionService;")
			.contains("private final RagAnswerService ragAnswerService;")
			.contains("@Qualifier(\"erpJdbcTemplate\") JdbcTemplate erpJdbcTemplate");
		assertThat(source.matches("(?s).*@Autowired\\s+private\\s+.*")).isFalse();
	}

	/**
	 * 验证异常导入留下的登记版本会在删除随机租户关系数据前重试向量清理。
	 *
	 * @throws Exception 文件读取失败时抛出
	 */
	@Test
	public void shouldRetryRegisteredVectorCleanupBeforeDeletingTenantRows() throws Exception {
		String source = Files.readString(
				Path.of("src/test/java/com/example/rag/rageval/RagEvaluationIT.java"));
		int retryIndex = source.indexOf(
			"cleanupRegisteredVersions(entCode, pendingVectorFailures, failures);");
		int deleteIndex = source.indexOf("deleteTenantRows(entCode, failures);");

		// 清理必须从登记表恢复版本身份，并且发生在关系数据删除之前。
		assertThat(source)
			.contains("FROM a_knowledge_document WHERE ent_code = ?")
			.contains("this.documentLoaderService.deleteManagedVersion(new ManagedDocumentMetadata(")
			.contains("return new RagEvaluationCleanupSummary(failures.isEmpty(), failures);");
		assertThat(retryIndex).isGreaterThanOrEqualTo(0);
		assertThat(deleteIndex).isGreaterThan(retryIndex);
	}

}
