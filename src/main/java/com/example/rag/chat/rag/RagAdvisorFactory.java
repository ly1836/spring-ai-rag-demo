package com.example.rag.chat.rag;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.rag.config.TenantContext;
import com.example.rag.dao.entity.KnowledgeBaseEntity;
import com.example.rag.knowledge.KnowledgeBaseService;
import com.example.rag.knowledge.KnowledgeDocumentService;
import com.example.rag.knowledge.dto.ManagedDocumentMetadata;

import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

/**
 * 受管模块化 RAG Advisor 工厂。
 */
@Component
public class RagAdvisorFactory {

	/** 单次受管检索允许的最大候选与最终返回数量。 */
	private static final int MAX_OVERSAMPLED_TOP_K = 24;

	/** 过短标题不用于来源限定，避免常见缩写误命中文档。 */
	private static final int MIN_MATCHED_TITLE_CODE_POINTS = 4;

	/** 识别被空格拆开的常见产品缩写后缀，例如 Rabbit MQ。 */
	private static final Pattern SPACED_PRODUCT_SUFFIX_PATTERN = Pattern.compile(
		"(?i)(?<![a-z0-9.+#-])([a-z][a-z0-9.+#-]{1,})\\s+(mq|db|ai|ui|os|api|sql)(?![a-z0-9.+#-])");

	private final VectorStore vectorStore;

	private final KnowledgeBaseService knowledgeBaseService;

	private final KnowledgeDocumentService knowledgeDocumentService;

	private final RagDocumentEligibilityFilter eligibilityFilter;

	private final RagContextFormatter contextFormatter;

	private final TaskExecutor ragTaskExecutor;

	/**
	 * 创建 RAG Advisor 工厂。
	 *
	 * @param vectorStore          PgVector 向量库
	 * @param knowledgeBaseService 知识库服务
	 * @param knowledgeDocumentService 文档版本服务
	 * @param eligibilityFilter    文档版本资格过滤器
	 * @param contextFormatter     编号上下文格式器
	 * @param ragTaskExecutor      应用级共享 RAG 执行器
	 */
	public RagAdvisorFactory(VectorStore vectorStore, KnowledgeBaseService knowledgeBaseService,
			KnowledgeDocumentService knowledgeDocumentService,
			RagDocumentEligibilityFilter eligibilityFilter, RagContextFormatter contextFormatter,
			@Qualifier("ragTaskExecutor") TaskExecutor ragTaskExecutor) {
		this.vectorStore = vectorStore;
		this.knowledgeBaseService = knowledgeBaseService;
		this.knowledgeDocumentService = knowledgeDocumentService;
		this.eligibilityFilter = eligibilityFilter;
		this.contextFormatter = contextFormatter;
		this.ragTaskExecutor = ragTaskExecutor;
	}

	/**
	 * 为单次问题创建知识库已校验的模块化 RAG 上下文。
	 *
	 * @param knowledgeBaseId 可空知识库 ID
	 * @param retrievalQuery  用户原始检索问题
	 * @param topK            最终召回数量
	 * @param threshold       相似度阈值
	 * @return 请求级 RAG 上下文
	 * @throws IllegalArgumentException topK 或阈值非法时抛出 PARAM_ERROR
	 * @throws IllegalStateException 知识库不存在或停用时抛出 BIZ_ERROR
	 */
	public RagRequestContext create(String knowledgeBaseId, String retrievalQuery,
			int topK, double threshold) {
		if (topK <= 0) {
			throw new IllegalArgumentException("RAG topK 必须大于 0");
		}
		if (topK > MAX_OVERSAMPLED_TOP_K) {
			throw new IllegalArgumentException("RAG topK 不能超过 " + MAX_OVERSAMPLED_TOP_K);
		}
		if (threshold < 0.0 || threshold > 1.0) {
			throw new IllegalArgumentException("RAG 相似度阈值必须在 0 到 1 之间");
		}
		KnowledgeBaseEntity knowledgeBase = this.knowledgeBaseService
			.resolveActiveKnowledgeBase(knowledgeBaseId);
		String entCode = TenantContext.requireEntCode();
		String matchedSource = resolveMatchedSource(knowledgeBase.getKnowledgeBaseId(), retrievalQuery);
		Filter.Expression filter = buildFilter(entCode, knowledgeBase.getKnowledgeBaseId(), matchedSource);
		String vectorQuery = enrichSpacedProductNames(retrievalQuery);
		int oversampledTopK = Math.min(MAX_OVERSAMPLED_TOP_K, Math.max(topK, topK * 3));
		DocumentRetriever rawRetriever = VectorStoreDocumentRetriever.builder()
			.vectorStore(this.vectorStore)
			.similarityThreshold(threshold)
			.topK(oversampledTopK)
			.filterExpression(filter)
			.build();
		DocumentRetriever eligibleRetriever = query -> {
			// 不使用模型增强提示；只在原问题后补充确定性的产品紧凑写法。
			Query originalQuery = Query.builder()
				.text(vectorQuery)
				.history(query.history())
				.context(query.context())
				.build();
			return this.eligibilityFilter.filter(knowledgeBase.getKnowledgeBaseId(),
				rawRetriever.retrieve(originalQuery), topK);
		};
		RetrievalAugmentationAdvisor advisor = RetrievalAugmentationAdvisor.builder()
			.documentRetriever(eligibleRetriever)
			.queryAugmenter(this.contextFormatter)
			.taskExecutor(this.ragTaskExecutor)
			.build();
		return new RagRequestContext(knowledgeBase.getKnowledgeBaseId(), advisor, eligibleRetriever);
	}

	/**
	 * 为被空格拆开的产品名追加紧凑别名，同时完整保留用户原问题。
	 *
	 * @param retrievalQuery 用户原始检索问题
	 * @return 原问题及去空格产品别名
	 */
	private String enrichSpacedProductNames(String retrievalQuery) {
		if (retrievalQuery == null || retrievalQuery.isBlank()) {
			return retrievalQuery;
		}
		Matcher matcher = SPACED_PRODUCT_SUFFIX_PATTERN.matcher(retrievalQuery);
		Set<String> aliases = new LinkedHashSet<>();
		while (matcher.find()) {
			aliases.add(matcher.group(1) + matcher.group(2));
		}
		return aliases.isEmpty() ? retrievalQuery : retrievalQuery + " " + String.join(" ", aliases);
	}

	/**
	 * 构建租户和知识库联合向量过滤条件。
	 *
	 * @param entCode         租户编码
	 * @param knowledgeBaseId 知识库 ID
	 * @return 向量过滤表达式
	 */
	private Filter.Expression buildFilter(String entCode, String knowledgeBaseId, String matchedSource) {
		FilterExpressionBuilder builder = new FilterExpressionBuilder();
		if (matchedSource != null) {
			return builder.and(
				builder.eq("ent_code", entCode),
				builder.and(
					builder.eq("knowledge_base_id", knowledgeBaseId),
					builder.and(
						builder.eq(EmbeddingModelMetadata.METADATA_KEY,
							EmbeddingModelMetadata.CURRENT_MODEL_ID),
						builder.eq("source", matchedSource))))
				.build();
		}
		return builder.and(
			builder.eq("ent_code", entCode),
			builder.and(
				builder.eq("knowledge_base_id", knowledgeBaseId),
				builder.eq(EmbeddingModelMetadata.METADATA_KEY,
					EmbeddingModelMetadata.CURRENT_MODEL_ID)))
			.build();
	}

	/**
	 * 当原问题明确包含唯一可用文档标题时返回其完整来源名称。
	 *
	 * @param knowledgeBaseId 知识库 ID
	 * @param retrievalQuery  用户原始问题
	 * @return 唯一匹配来源；无明确或存在多个匹配时返回 null
	 */
	private String resolveMatchedSource(String knowledgeBaseId, String retrievalQuery) {
		String normalizedQuery = normalizeForTitleMatch(retrievalQuery);
		if (normalizedQuery.isEmpty()) {
			return null;
		}
		List<ManagedDocumentMetadata> readyDocuments =
			this.knowledgeDocumentService.findReadyDocuments(knowledgeBaseId);
		Set<String> matchedSources = new LinkedHashSet<>();
		for (ManagedDocumentMetadata document : readyDocuments) {
			String normalizedTitle = normalizeForTitleMatch(removeExtension(document.sourceName()));
			if (normalizedTitle.codePointCount(0, normalizedTitle.length())
					>= MIN_MATCHED_TITLE_CODE_POINTS && normalizedQuery.contains(normalizedTitle)) {
				matchedSources.add(document.sourceName());
			}
		}
		return matchedSources.size() == 1 ? matchedSources.iterator().next() : null;
	}

	/**
	 * 移除文件扩展名，保留原始标题用于问题匹配。
	 *
	 * @param sourceName 来源文件名
	 * @return 不含末尾扩展名的标题
	 */
	private String removeExtension(String sourceName) {
		if (sourceName == null) {
			return "";
		}
		int separator = sourceName.lastIndexOf('.');
		return separator > 0 ? sourceName.substring(0, separator) : sourceName;
	}

	/**
	 * 忽略大小写、空白和标点生成轻量标题匹配文本。
	 *
	 * @param value 原始问题或标题
	 * @return 仅保留字母和数字的规范文本
	 */
	private String normalizeForTitleMatch(String value) {
		if (value == null || value.isBlank()) {
			return "";
		}
		String normalized = value.toLowerCase(Locale.ROOT);
		StringBuilder result = new StringBuilder(normalized.length());
		normalized.codePoints()
			.filter(Character::isLetterOrDigit)
			.forEach(result::appendCodePoint);
		return result.toString();
	}

}
