package com.example.rag.rageval;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.example.rag.chat.DocumentLoaderService;
import com.example.rag.chat.ModelRegistry;
import com.example.rag.chat.client.AssistantClientProvider;
import com.example.rag.chat.output.AssistantAnswerSanitizer;
import com.example.rag.chat.rag.RagAnswerResult;
import com.example.rag.chat.rag.RagAnswerService;
import com.example.rag.chat.rag.RagCitationNumberExtractor;
import com.example.rag.chat.rag.RagRequestContext;
import com.example.rag.chat.rag.RagRetrievalDefaults;
import com.example.rag.config.ModelProperties.ModelItem;
import com.example.rag.config.TenantContext;
import com.example.rag.knowledge.KnowledgeBaseService;
import com.example.rag.knowledge.KnowledgeDocumentIngestionService;
import com.example.rag.knowledge.dto.ManagedDocumentMetadata;
import com.example.rag.vo.ChatVO;
import com.example.rag.vo.KnowledgeVO;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.evaluation.FactCheckingEvaluator;
import org.springframework.ai.chat.evaluation.RelevancyEvaluator;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.document.Document;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.evaluation.EvaluationResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 复用生产导入、检索、上下文和引用组件的真实 RAG 评测。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RagEvaluationIT {

	private static final Logger log = LoggerFactory.getLogger(RagEvaluationIT.class);

	/** v1 评测集目录。 */
	private static final Path DATASET_DIRECTORY = Path.of("src", "test", "resources", "rag-eval", "v1");

	/** 正式评测报告目录。 */
	private static final Path REPORT_DIRECTORY = Path.of("target", "rag-eval");

	/** 随机租户使用的固定评测用户。 */
	private static final String EVALUATION_USER_ID = "eval-user";

	/** 生产知识文档导入编排服务。 */
	private final KnowledgeDocumentIngestionService ingestionService;

	/** 生产向量写入和精确清理服务。 */
	private final DocumentLoaderService documentLoaderService;

	/** 生产知识库管理服务。 */
	private final KnowledgeBaseService knowledgeBaseService;

	/** 无会话和计费职责的生产 RAG 服务。 */
	private final RagAnswerService ragAnswerService;

	/** 知识问答客户端提供器。 */
	private final AssistantClientProvider assistantClientProvider;

	/** 模型注册中心。 */
	private final ModelRegistry modelRegistry;

	/** 最终答案净化器。 */
	private final AssistantAnswerSanitizer answerSanitizer;

	/** 评测数据和报告使用的 JSON 序列化器。 */
	private final ObjectMapper objectMapper;

	/** 读取评测配置的 Spring 环境。 */
	private final Environment environment;

	/** 仅用于创建和清理随机评测租户的 ERP 数据源。 */
	private final JdbcTemplate erpJdbcTemplate;

	/**
	 * 创建使用生产组件的真实 RAG 评测。
	 *
	 * @param ingestionService 知识文档导入编排服务
	 * @param documentLoaderService 向量写入和清理服务
	 * @param knowledgeBaseService 知识库管理服务
	 * @param ragAnswerService RAG 回答服务
	 * @param assistantClientProvider 知识问答客户端提供器
	 * @param modelRegistry 模型注册中心
	 * @param answerSanitizer 最终答案净化器
	 * @param objectMapper JSON 序列化器
	 * @param environment Spring 环境
	 * @param erpJdbcTemplate ERP 数据源访问模板
	 */
	@Autowired
	public RagEvaluationIT(KnowledgeDocumentIngestionService ingestionService,
			DocumentLoaderService documentLoaderService, KnowledgeBaseService knowledgeBaseService,
			RagAnswerService ragAnswerService, AssistantClientProvider assistantClientProvider,
			ModelRegistry modelRegistry, AssistantAnswerSanitizer answerSanitizer,
			ObjectMapper objectMapper, Environment environment,
			@Qualifier("erpJdbcTemplate") JdbcTemplate erpJdbcTemplate) {
		this.ingestionService = ingestionService;
		this.documentLoaderService = documentLoaderService;
		this.knowledgeBaseService = knowledgeBaseService;
		this.ragAnswerService = ragAnswerService;
		this.assistantClientProvider = assistantClientProvider;
		this.modelRegistry = modelRegistry;
		this.answerSanitizer = answerSanitizer;
		this.objectMapper = objectMapper;
		this.environment = environment;
		this.erpJdbcTemplate = erpJdbcTemplate;
	}

	/**
	 * 执行完整 v1 真实评测并以安全隔离、引用、关键错误结论和 Recall 基线作为硬门禁。
	 */
	@Test
	public void shouldEvaluateProductionRagWithIsolatedData() {
		verifyEvaluationProfile();
		RagEvaluationDataset dataset = new RagEvaluationDatasetLoader(this.objectMapper)
			.load(DATASET_DIRECTORY);
		ModelSelection model = resolveModelSelection();
		String primaryTenant = randomTenantCode();
		String otherTenant = randomTenantCode();
		List<ImportedDocument> importedDocuments = new ArrayList<>();
		Map<String, String> primaryDocumentIds = new HashMap<>();
		Map<String, DocumentScope> documentScopes = new HashMap<>();
		List<RagEvaluationCaseResult> results = new ArrayList<>();
		RagEvaluationGateResult gate = null;
		Throwable evaluationFailure = null;
		try {
			createTenant(primaryTenant, "RAG 评测主租户");
			createTenant(otherTenant, "RAG 评测干扰租户");
			String primaryKnowledgeBase = resolveDefaultKnowledgeBase(primaryTenant);
			String otherKnowledgeBase = createOtherKnowledgeBase(primaryTenant);
			String otherTenantKnowledgeBase = resolveDefaultKnowledgeBase(otherTenant);

			importPrimaryDocuments(dataset, primaryTenant, primaryKnowledgeBase,
				primaryDocumentIds, documentScopes, importedDocuments);
			importInterferenceDocument(dataset, primaryTenant, otherKnowledgeBase,
				"other-kb", documentScopes, importedDocuments);
			importInterferenceDocument(dataset, otherTenant, otherTenantKnowledgeBase,
				"other-tenant", documentScopes, importedDocuments);

			JudgeEvaluators judges = createJudgeEvaluators(model);
			for (RagEvaluationCase evaluationCase : dataset.cases()) {
				if (!"primary".equals(evaluationCase.targetKnowledgeBase())) {
					throw new IllegalArgumentException(
						"RAG 评测知识库别名不受支持: " + evaluationCase.targetKnowledgeBase());
				}
				long caseStartNanos = System.nanoTime();
				try {
					results.add(runCase(evaluationCase, primaryTenant, primaryKnowledgeBase,
						primaryDocumentIds, documentScopes, model, judges));
				}
				catch (RuntimeException ex) {
					long latencyMs = (System.nanoTime() - caseStartNanos) / 1_000_000L;
					results.add(failedCase(evaluationCase, model.provider(), latencyMs, ex));
					throw new IllegalStateException(
						"RAG 评测用例执行失败: " + evaluationCase.caseId(), ex);
				}
			}
		}
		catch (RuntimeException | Error ex) {
			evaluationFailure = ex;
			throw ex;
		}
		finally {
			RagEvaluationCleanupSummary cleanup = cleanupEvaluationData(
				importedDocuments, List.of(primaryTenant, otherTenant));
			TenantContext.clear();
			if (evaluationFailure == null) {
				gate = writeReport(dataset, results, cleanup);
			}
			else {
				gate = writePartialReport(dataset, results, cleanup);
			}
		}
		assertTrue(gate != null && gate.passed(), "RAG 评测硬门禁失败: "
			+ (gate == null ? "未生成门禁结果" : String.join("；", gate.failures())));
	}

	/**
	 * 确认真实评测只能由显式 Maven Profile 启动。
	 */
	private void verifyEvaluationProfile() {
		if (!Boolean.parseBoolean(System.getProperty("rag.eval.enabled", "false"))) {
			throw new IllegalStateException(
				"真实 RAG 评测未启用，请使用 mvn -Prag-eval verify");
		}
	}

	/**
	 * 解析并校验评测模型、Provider 和密钥配置。
	 *
	 * @return 可用模型选择
	 */
	private ModelSelection resolveModelSelection() {
		String requestedModelId = System.getProperty("rag.eval.modelId", "").trim();
		ModelItem item = this.modelRegistry.getModelItem(requestedModelId);
		if (item == null || (!requestedModelId.isBlank() && !requestedModelId.equals(item.getId()))) {
			throw new IllegalStateException("RAG 评测模型不存在: " + requestedModelId);
		}
		if (item.getProvider() == null || item.getProvider().isBlank()
				|| item.getModelName() == null || item.getModelName().isBlank()) {
			throw new IllegalStateException("RAG 评测模型配置不完整: " + item.getId());
		}
		verifyProviderKey(item.getProvider());
		ChatModel chatModel = this.modelRegistry.getChatModel(item.getId());
		if (chatModel == null) {
			throw new IllegalStateException("RAG 评测模型 Bean 不可用: " + item.getId());
		}
		return new ModelSelection(item.getId(), item.getProvider(), item.getModelName(), chatModel);
	}

	/**
	 * 校验当前 Provider 的模型密钥不是空值或示例占位符。
	 *
	 * @param provider Provider 标识
	 */
	private void verifyProviderKey(String provider) {
		String propertyName = switch (provider) {
			case "deepseek" -> "spring.ai.deepseek.api-key";
			case "openai" -> "spring.ai.openai.api-key";
			case "google-genai" -> "spring.ai.google.genai.api-key";
			default -> throw new IllegalStateException("RAG 评测暂不支持 Provider: " + provider);
		};
		String apiKey = this.environment.getProperty(propertyName, "").trim();
		String normalized = apiKey.toLowerCase(Locale.ROOT);
		if (apiKey.isBlank() || normalized.contains("your-") || normalized.contains("-your")) {
			throw new IllegalStateException("RAG 评测缺少有效模型 Key: " + propertyName);
		}
	}

	/**
	 * 创建随机隔离租户。
	 *
	 * @param entCode 租户编码
	 * @param entName 租户名称
	 */
	private void createTenant(String entCode, String entName) {
		this.erpJdbcTemplate.update(
			"INSERT INTO a_tenant (ent_code, ent_name, status) VALUES (?, ?, 'active')",
			entCode, entName);
	}

	/**
	 * 解析随机租户的默认知识库。
	 *
	 * @param entCode 租户编码
	 * @return 默认知识库 ID
	 */
	private String resolveDefaultKnowledgeBase(String entCode) {
		setTenantContext(entCode);
		return this.knowledgeBaseService.listKnowledgeBases().stream()
			.filter(KnowledgeVO.KnowledgeBaseItem::isDefault)
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("RAG 评测默认知识库创建失败"))
			.knowledgeBaseId();
	}

	/**
	 * 创建同租户的干扰知识库。
	 *
	 * @param entCode 租户编码
	 * @return 干扰知识库 ID
	 */
	private String createOtherKnowledgeBase(String entCode) {
		setTenantContext(entCode);
		return this.knowledgeBaseService.createKnowledgeBase(
			new KnowledgeVO.CreateKnowledgeBaseRequest("RAG 评测干扰知识库", "验证知识库隔离"))
			.knowledgeBaseId();
	}

	/**
	 * 通过生产导入组件写入目标知识库 fixture。
	 *
	 * @param dataset             评测集
	 * @param entCode             目标租户
	 * @param knowledgeBaseId     目标知识库
	 * @param primaryDocumentIds  fixture 到稳定文档 ID 的映射
	 * @param documentScopes      稳定文档 ID 到实际边界的映射
	 * @param importedDocuments   已导入文档清单
	 */
	private void importPrimaryDocuments(RagEvaluationDataset dataset, String entCode,
			String knowledgeBaseId, Map<String, String> primaryDocumentIds,
			Map<String, DocumentScope> documentScopes, List<ImportedDocument> importedDocuments) {
		dataset.documents().entrySet().stream()
			.filter(entry -> !"isolation-interference".equals(entry.getKey()))
			.sorted(Map.Entry.comparingByKey())
			.forEach(entry -> {
				KnowledgeVO.KnowledgeDocumentImportResponse response = importFixture(
					entCode, knowledgeBaseId, entry.getKey(), entry.getValue());
				primaryDocumentIds.put(entry.getKey(), response.documentId());
				documentScopes.put(response.documentId(), new DocumentScope(
					entry.getKey(), entCode, knowledgeBaseId, "目标知识库"));
				importedDocuments.add(toImportedDocument(entCode, response));
			});
	}

	/**
	 * 在非目标边界导入专用干扰文档。
	 *
	 * @param dataset           评测集
	 * @param entCode           干扰租户
	 * @param knowledgeBaseId   干扰知识库
	 * @param boundaryType      边界类型
	 * @param documentScopes    稳定文档 ID 到实际边界的映射
	 * @param importedDocuments 已导入文档清单
	 */
	private void importInterferenceDocument(RagEvaluationDataset dataset, String entCode,
			String knowledgeBaseId, String boundaryType, Map<String, DocumentScope> documentScopes,
			List<ImportedDocument> importedDocuments) {
		Path path = dataset.documents().get("isolation-interference");
		KnowledgeVO.KnowledgeDocumentImportResponse response = importFixture(
			entCode, knowledgeBaseId, boundaryType + "-isolation-interference", path);
		documentScopes.put(response.documentId(), new DocumentScope(
			boundaryType + ":isolation-interference", entCode, knowledgeBaseId, boundaryType));
		importedDocuments.add(toImportedDocument(entCode, response));
	}

	/**
	 * 读取 fixture 并调用生产纯文本导入链路。
	 *
	 * @param entCode         租户编码
	 * @param knowledgeBaseId 知识库 ID
	 * @param fixtureId       fixture ID
	 * @param path            fixture 文件
	 * @return 导入响应
	 */
	private KnowledgeVO.KnowledgeDocumentImportResponse importFixture(String entCode,
			String knowledgeBaseId, String fixtureId, Path path) {
		if (path == null) {
			throw new IllegalArgumentException("RAG 评测 fixture 不存在: " + fixtureId);
		}
		try {
			setTenantContext(entCode);
			String content = Files.readString(path, StandardCharsets.UTF_8);
			return this.ingestionService.importText(
				knowledgeBaseId, null, fixtureId + ".txt", content);
		}
		catch (java.io.IOException ex) {
			throw new IllegalStateException("读取 RAG 评测 fixture 失败: " + fixtureId, ex);
		}
	}

	/**
	 * 构造已导入文档的精确清理身份。
	 *
	 * @param entCode 租户编码
	 * @param response 导入响应
	 * @return 清理身份
	 */
	private ImportedDocument toImportedDocument(String entCode,
			KnowledgeVO.KnowledgeDocumentImportResponse response) {
		return new ImportedDocument(entCode, response.knowledgeBaseId(), response.documentId(),
			response.version(), response.sourceName());
	}

	/**
	 * 创建相关性和事实一致性 Judge。
	 *
	 * @param model 模型选择
	 * @return Judge 组合
	 */
	private JudgeEvaluators createJudgeEvaluators(ModelSelection model) {
		RagEvaluationUsageTracker usageTracker = new RagEvaluationUsageTracker(model.chatModel());
		ChatClient.Builder relevancyBuilder = ChatClient.builder(usageTracker)
			.defaultOptions(ChatOptions.builder().model(model.modelName()));
		ChatClient.Builder factCheckingBuilder = ChatClient.builder(usageTracker)
			.defaultOptions(ChatOptions.builder().model(model.modelName()));
		return new JudgeEvaluators(
			RelevancyEvaluator.builder().chatClientBuilder(relevancyBuilder).build(),
			FactCheckingEvaluator.builder(factCheckingBuilder).build(), usageTracker);
	}

	/**
	 * 使用生产 RAG 链路执行单条评测用例。
	 *
	 * @param evaluationCase      评测用例
	 * @param entCode             目标租户
	 * @param knowledgeBaseId     目标知识库
	 * @param primaryDocumentIds  fixture 到稳定文档 ID 的映射
	 * @param documentScopes      已知文档实际边界
	 * @param model               模型选择
	 * @param judges              Judge 组合
	 * @return 单条结果
	 */
	private RagEvaluationCaseResult runCase(RagEvaluationCase evaluationCase, String entCode,
			String knowledgeBaseId, Map<String, String> primaryDocumentIds,
			Map<String, DocumentScope> documentScopes, ModelSelection model,
			JudgeEvaluators judges) {
		setTenantContext(entCode);
		long startNanos = System.nanoTime();
		RagRequestContext context = this.ragAnswerService.prepare(knowledgeBaseId,
			evaluationCase.question(),
			RagRetrievalDefaults.KNOWLEDGE_TOP_K,
			RagRetrievalDefaults.KNOWLEDGE_SIMILARITY_THRESHOLD);
		ChatResponse response = this.assistantClientProvider.resolveKnowledgeClient(model.modelId())
			.prompt()
			.options(ChatOptions.builder().model(model.modelName()))
			.advisors(context.advisor())
			.user(evaluationCase.question())
			.call()
			.chatResponse();
		if (response == null || response.getResult() == null
				|| response.getResult().getOutput() == null) {
			throw new IllegalStateException("模型未返回可用回答");
		}
		String answer = this.answerSanitizer.sanitize(response.getResult().getOutput().getText());
		RagAnswerResult ragResult = this.ragAnswerService.complete(answer, response, context);
		long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
		List<String> retrievedDocumentIds = mapRetrievedDocuments(
			ragResult.documents(), documentScopes);
		List<String> expectedDocumentIds = evaluationCase.expectedDocumentIds().stream()
			.filter(primaryDocumentIds::containsKey)
			.toList();
		List<String> boundaryViolations = findBoundaryViolations(
			ragResult.documents(), entCode, knowledgeBaseId, documentScopes);
		Set<CitationEvidenceIdentity> evidenceIdentities = new HashSet<>();
		for (Document document : ragResult.documents()) {
			// 硬门禁按知识库、文档版本和具体分片完整比对，不能只比较文档 ID。
			evidenceIdentities.add(new CitationEvidenceIdentity(
				readMetadata(document, "knowledge_base_id"),
				readMetadata(document, "document_id"),
				readMetadata(document, "document_version"),
				readMetadata(document, "chunk_id"),
				readMetadata(document, "chunk_index")));
		}
		boolean citationsWithinEvidence = ragResult.citations().stream()
			.allMatch(citation -> evidenceIdentities.contains(new CitationEvidenceIdentity(
				citation.knowledgeBaseId(), citation.documentId(),
				String.valueOf(citation.documentVersion()), citation.chunkId(),
				String.valueOf(citation.chunkIndex()))));
		int totalCitations = countDistinctCitations(answer);
		Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
		int promptTokens = readTokenCount(usage == null ? null : usage.getPromptTokens());
		int completionTokens = readTokenCount(usage == null ? null : usage.getCompletionTokens());
		RagEvaluationAnswerCheck answerCheck = new RagEvaluationAnswerChecker().check(answer,
			evaluationCase.keyFacts(), evaluationCase.forbiddenAnswerPhrases());
		JudgeScores scores = evaluateWithJudges(
			judges, evaluationCase.question(), ragResult.documents(), answer);
		Double answerCost = estimateCost(promptTokens, completionTokens);
		Double judgeCost = estimateCost(scores.promptTokens(), scores.completionTokens());
		return new RagEvaluationCaseResult(evaluationCase.caseId(), evaluationCase.answerable(),
			expectedDocumentIds, retrievedDocumentIds,
			RagEvaluationRefusalClassifier.isRefusal(answer, evaluationCase.question()),
			ragResult.citations().size(),
			totalCitations, citationsWithinEvidence, boundaryViolations.size(), boundaryViolations,
			answerCheck.matchedKeyFactCount(), answerCheck.totalKeyFactCount(),
			answerCheck.criticalFactViolations(), latencyMs, promptTokens, completionTokens,
			scores.promptTokens(), scores.completionTokens(),
			model.provider(), answerCost, judgeCost,
			estimateCost(promptTokens + scores.promptTokens(),
				completionTokens + scores.completionTokens()),
			scores.relevancy(), scores.factChecking(),
			scores.error(), answer, null);
	}

	/**
	 * 将实际稳定文档 ID 映射回可审查 fixture ID。
	 *
	 * @param documents      召回分片
	 * @param documentScopes 已知文档边界
	 * @return 最多前五个召回文档 ID
	 */
	private List<String> mapRetrievedDocuments(List<Document> documents,
			Map<String, DocumentScope> documentScopes) {
		List<String> documentIds = new ArrayList<>();
		for (Document document : documents.stream().limit(5).toList()) {
			String actualDocumentId = readMetadata(document, "document_id");
			DocumentScope scope = documentScopes.get(actualDocumentId);
			documentIds.add(scope == null ? "unknown:" + actualDocumentId : scope.fixtureId());
		}
		return List.copyOf(documentIds);
	}

	/**
	 * 找出召回分片中的跨租户、跨知识库或隔离元数据缺失。
	 *
	 * @param documents          召回分片
	 * @param expectedTenant     目标租户
	 * @param expectedKnowledgeBase 目标知识库
	 * @param documentScopes     已知文档实际边界
	 * @return 越界明细
	 */
	private List<String> findBoundaryViolations(List<Document> documents, String expectedTenant,
			String expectedKnowledgeBase, Map<String, DocumentScope> documentScopes) {
		List<String> violations = new ArrayList<>();
		for (Document document : documents) {
			String documentId = readMetadata(document, "document_id");
			String tenant = readMetadata(document, "ent_code");
			String knowledgeBase = readMetadata(document, "knowledge_base_id");
			DocumentScope scope = documentScopes.get(documentId);
			if (tenant.isBlank() || knowledgeBase.isBlank()) {
				violations.add("缺失隔离元数据: documentId=" + documentId);
			}
			else if (!expectedTenant.equals(tenant)
					|| (scope != null && !expectedTenant.equals(scope.entCode()))) {
				violations.add("跨租户: documentId=" + documentId + "，entCode=" + tenant);
			}
			else if (!expectedKnowledgeBase.equals(knowledgeBase)
					|| (scope != null && !expectedKnowledgeBase.equals(scope.knowledgeBaseId()))) {
				violations.add("跨知识库: documentId=" + documentId
					+ "，knowledgeBaseId=" + knowledgeBase);
			}
		}
		return List.copyOf(violations);
	}

	/**
	 * 执行两个 LLM Judge，失败时保留原因但不单独触发硬门禁。
	 *
	 * @param judges    Judge 组合
	 * @param question  用户问题
	 * @param documents 本轮证据
	 * @param answer    最终回答
	 * @return Judge 分数或失败原因
	 */
	private JudgeScores evaluateWithJudges(JudgeEvaluators judges, String question,
			List<Document> documents, String answer) {
		RagEvaluationUsageTracker.UsageSnapshot before = judges.usageTracker().snapshot();
		EvaluationRequest request = new EvaluationRequest(question, documents, answer);
		Float relevancy = null;
		Float factChecking = null;
		List<String> errors = new ArrayList<>();
		try {
			EvaluationResponse response = judges.relevancy().evaluate(request);
			relevancy = response.getScore();
		}
		catch (RuntimeException ex) {
			errors.add("相关性 Judge 失败: " + safeErrorMessage(ex));
		}
		try {
			EvaluationResponse response = judges.factChecking().evaluate(request);
			factChecking = response.isPass() ? 1.0F : 0.0F;
		}
		catch (RuntimeException ex) {
			errors.add("事实一致性 Judge 失败: " + safeErrorMessage(ex));
		}
		RagEvaluationUsageTracker.UsageSnapshot usage = judges.usageTracker().snapshot()
			.subtract(before);
		return new JudgeScores(relevancy, factChecking,
			errors.isEmpty() ? null : String.join("；", errors),
			usage.promptTokens(), usage.completionTokens());
	}

	/**
	 * 统计回答中不重复的引用编号，包括不在白名单中的伪造编号。
	 *
	 * @param answer 模型回答
	 * @return 引用编号数量
	 */
	private int countDistinctCitations(String answer) {
		// 与生产引用校验共享同一规则，代码或转义字面量不计入无效引用。
		return new HashSet<>(RagCitationNumberExtractor.extract(answer)).size();
	}

	/**
	 * 读取非负 Token 数。
	 *
	 * @param value Provider usage 值
	 * @return 非负 Token 数
	 */
	private int readTokenCount(Integer value) {
		return value == null ? 0 : Math.max(value, 0);
	}

	/**
	 * 根据显式配置的百万 Token 单价估算成本。
	 *
	 * @param promptTokens     输入 Token
	 * @param completionTokens 输出 Token
	 * @return 成本；未配置单价时返回 null
	 */
	private Double estimateCost(int promptTokens, int completionTokens) {
		Double inputPrice = readOptionalPrice("rag.eval.inputPricePerMillion");
		Double outputPrice = readOptionalPrice("rag.eval.outputPricePerMillion");
		if (inputPrice == null || outputPrice == null) {
			return null;
		}
		return promptTokens * inputPrice / 1_000_000.0
			+ completionTokens * outputPrice / 1_000_000.0;
	}

	/**
	 * 读取可空非负价格配置。
	 *
	 * @param propertyName 系统属性名
	 * @return 非负价格或 null
	 */
	private Double readOptionalPrice(String propertyName) {
		String value = System.getProperty(propertyName, "").trim();
		if (value.isBlank()) {
			return null;
		}
		try {
			double price = Double.parseDouble(value);
			if (price < 0.0) {
				throw new IllegalArgumentException("RAG 评测价格不能为负数: " + propertyName);
			}
			return price;
		}
		catch (NumberFormatException ex) {
			throw new IllegalArgumentException("RAG 评测价格格式非法: " + propertyName, ex);
		}
	}

	/**
	 * 从文档读取可空元数据文本。
	 *
	 * @param document 文档分片
	 * @param key      元数据键
	 * @return 元数据文本
	 */
	private String readMetadata(Document document, String key) {
		Object value = document == null ? null : document.getMetadata().get(key);
		return value == null ? "" : String.valueOf(value);
	}

	/**
	 * 构造明确记录失败原因的单条结果。
	 *
	 * @param evaluationCase 评测用例
	 * @param provider       Provider
	 * @param latencyMs      失败前已产生的端到端耗时
	 * @param error          执行异常
	 * @return 失败结果
	 */
	private RagEvaluationCaseResult failedCase(RagEvaluationCase evaluationCase,
			String provider, long latencyMs, RuntimeException error) {
		return new RagEvaluationCaseResult(evaluationCase.caseId(), evaluationCase.answerable(),
			evaluationCase.expectedDocumentIds(), List.of(), false, 0, 0, true, 0,
			List.of(), 0, evaluationCase.keyFacts().size(), List.of(), latencyMs,
			0, 0, 0, 0, provider, null, null, null,
			null, null, null, "",
			safeErrorMessage(error));
	}

	/**
	 * 汇总指标、执行硬门禁并写入正式报告。
	 *
	 * @param dataset 评测集
	 * @param results 逐例结果
	 * @param cleanup 隔离数据清理结果
	 * @return 硬门禁结果
	 */
	private RagEvaluationGateResult writeReport(RagEvaluationDataset dataset,
			List<RagEvaluationCaseResult> results, RagEvaluationCleanupSummary cleanup) {
		RagEvaluationSummary summary = new RagEvaluationMetrics().summarize(results);
		RagEvaluationGateResult gate = new RagEvaluationGate()
			.evaluate(summary, dataset.baseline(), results, dataset.cases().size(), cleanup);
		new RagEvaluationReportWriter(this.objectMapper)
			.write(REPORT_DIRECTORY, dataset.version(), summary, gate, cleanup, results);
		return gate;
	}

	/**
	 * 异常结束时尽力保存已完成用例报告，且不覆盖原始异常。
	 *
	 * @param dataset 评测集
	 * @param results 已完成结果
	 * @param cleanup 隔离数据清理结果
	 * @return 包含清理状态的硬门禁结果
	 */
	private RagEvaluationGateResult writePartialReport(RagEvaluationDataset dataset,
			List<RagEvaluationCaseResult> results, RagEvaluationCleanupSummary cleanup) {
		try {
			return writeReport(dataset, results, cleanup);
		}
		catch (RuntimeException ex) {
			log.warn("RAG 评测异常结束后写入部分报告失败: {}", safeErrorMessage(ex));
			RagEvaluationSummary summary = new RagEvaluationMetrics().summarize(results);
			return new RagEvaluationGate().evaluate(
				summary, dataset.baseline(), results, dataset.cases().size(), cleanup);
		}
	}

	/**
	 * 按精确租户、知识库、文档和版本边界清理向量与 MySQL 数据。
	 *
	 * @param importedDocuments 已成功导入的文档
	 * @param tenantCodes       本轮随机租户
	 * @return 全部清理步骤完成后的汇总结果
	 */
	private RagEvaluationCleanupSummary cleanupEvaluationData(List<ImportedDocument> importedDocuments,
			List<String> tenantCodes) {
		Map<String, String> pendingVectorFailures = new LinkedHashMap<>();
		List<String> failures = new ArrayList<>();
		List<ImportedDocument> reversed = importedDocuments.stream()
			.sorted(Comparator.comparing(ImportedDocument::entCode).reversed())
			.toList();
		for (ImportedDocument document : reversed) {
			setTenantContext(document.entCode());
			try {
				this.ingestionService.deleteDocument(
					document.knowledgeBaseId(), document.documentId());
			}
			catch (RuntimeException ex) {
				// 后续仍会按随机租户直接删除关系数据，并从登记表重试精确向量清理。
				log.warn("RAG 评测通过服务清理文档失败，继续执行兜底清理: documentId={}, error={}",
					document.documentId(), safeErrorMessage(ex));
			}
			cleanupVectorVersion(document, pendingVectorFailures);
		}
		for (String entCode : tenantCodes) {
			// 从登记表恢复失败或中断导入的版本身份，并在删除关系数据前重试向量清理。
			cleanupRegisteredVersions(entCode, pendingVectorFailures, failures);
			deleteTenantRows(entCode, failures);
		}
		failures.addAll(pendingVectorFailures.values());
		return new RagEvaluationCleanupSummary(failures.isEmpty(), failures);
	}

	/**
	 * 仅按本轮随机租户删除关系数据。
	 *
	 * @param entCode 随机租户编码
	 * @param failures 最终清理失败汇总
	 */
	private void deleteTenantRows(String entCode, List<String> failures) {
		cleanupDatabaseStep(failures, "删除文档登记失败: entCode=" + entCode,
			() -> this.erpJdbcTemplate.update(
				"DELETE FROM a_knowledge_document WHERE ent_code = ?", entCode));
		cleanupDatabaseStep(failures, "删除知识库登记失败: entCode=" + entCode,
			() -> this.erpJdbcTemplate.update(
				"DELETE FROM a_knowledge_base WHERE ent_code = ?", entCode));
		cleanupDatabaseStep(failures, "删除租户用户失败: entCode=" + entCode,
			() -> this.erpJdbcTemplate.update(
				"DELETE FROM a_tenant_user WHERE ent_code = ?", entCode));
		cleanupDatabaseStep(failures, "删除随机租户失败: entCode=" + entCode,
			() -> this.erpJdbcTemplate.update(
				"DELETE FROM a_tenant WHERE ent_code = ?", entCode));
	}

	/**
	 * 设置当前评测租户和固定评测用户。
	 *
	 * @param entCode 租户编码
	 */
	private void setTenantContext(String entCode) {
		TenantContext.clear();
		TenantContext.setEntCode(entCode);
		TenantContext.setUserId(EVALUATION_USER_ID);
	}

	/**
	 * 生成满足数据库长度边界的随机租户编码。
	 *
	 * @return 随机租户编码
	 */
	private String randomTenantCode() {
		return "EVAL_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
	}

	/**
	 * 提取适合报告和日志的单行异常摘要。
	 *
	 * @param error 异常
	 * @return 单行错误摘要
	 */
	private String safeErrorMessage(Throwable error) {
		String message = error == null ? "未知错误" : error.getMessage();
		return message == null || message.isBlank() ? error.getClass().getSimpleName()
			: message.replace('\r', ' ').replace('\n', ' ');
	}

	/**
	 * 从随机租户的登记表恢复全部文档版本，并逐一重试精确向量清理。
	 *
	 * @param entCode 随机租户编码
	 * @param pendingVectorFailures 尚未恢复的向量清理失败
	 * @param failures              最终清理失败汇总
	 */
	private void cleanupRegisteredVersions(String entCode,
			Map<String, String> pendingVectorFailures, List<String> failures) {
		setTenantContext(entCode);
		List<ImportedDocument> registeredDocuments;
		try {
			// 查询包含 processing、failed 在内的全部登记版本，覆盖导入返回前失败的场景。
			registeredDocuments = this.erpJdbcTemplate.query(
				"SELECT ent_code, knowledge_base_id, document_id, version, source_name "
					+ "FROM a_knowledge_document WHERE ent_code = ?",
				(resultSet, rowNumber) -> new ImportedDocument(
					resultSet.getString("ent_code"), resultSet.getString("knowledge_base_id"),
					resultSet.getString("document_id"), resultSet.getInt("version"),
					resultSet.getString("source_name")),
				entCode);
		}
		catch (RuntimeException ex) {
			String failure = "读取待清理文档版本失败: entCode=" + entCode
				+ "，error=" + safeErrorMessage(ex);
			failures.add(failure);
			log.warn("RAG 评测{}", failure);
			return;
		}
		for (ImportedDocument document : registeredDocuments) {
			cleanupVectorVersion(document, pendingVectorFailures);
		}
	}

	/**
	 * 使用完整稳定身份删除一个文档版本向量，并保留最后一次失败状态。
	 *
	 * @param document             文档版本身份
	 * @param pendingVectorFailures 尚未恢复的向量清理失败
	 */
	private void cleanupVectorVersion(ImportedDocument document,
			Map<String, String> pendingVectorFailures) {
		String identity = document.entCode() + "|" + document.knowledgeBaseId()
			+ "|" + document.documentId() + "|" + document.version();
		try {
			this.documentLoaderService.deleteManagedVersion(new ManagedDocumentMetadata(
				document.entCode(), document.knowledgeBaseId(), document.documentId(),
				document.version(), document.sourceName()));
			pendingVectorFailures.remove(identity);
		}
		catch (RuntimeException ex) {
			String failure = "删除文档向量失败: documentId=" + document.documentId()
				+ "，version=" + document.version() + "，error=" + safeErrorMessage(ex);
			pendingVectorFailures.put(identity, failure);
			log.warn("RAG 评测{}", failure);
		}
	}

	/**
	 * 执行一个独立关系数据清理步骤，失败时继续执行后续步骤。
	 *
	 * @param failures   最终清理失败汇总
	 * @param description 清理步骤说明
	 * @param action      清理动作
	 */
	private void cleanupDatabaseStep(List<String> failures, String description, Runnable action) {
		try {
			action.run();
		}
		catch (RuntimeException ex) {
			String failure = description + "，error=" + safeErrorMessage(ex);
			failures.add(failure);
			log.warn("RAG 评测{}", failure);
		}
	}

	/**
	 * 评测模型选择。
	 *
	 * @param modelId   模型 ID
	 * @param provider  Provider
	 * @param modelName 实际模型名
	 * @param chatModel Spring AI 模型
	 */
	private record ModelSelection(String modelId, String provider, String modelName,
			ChatModel chatModel) {
	}

	/**
	 * LLM Judge 组合。
	 *
	 * @param relevancy   相关性 Judge
	 * @param factChecking 事实一致性 Judge
	 * @param usageTracker Judge 模型用量记录器
	 */
	private record JudgeEvaluators(RelevancyEvaluator relevancy,
			FactCheckingEvaluator factChecking, RagEvaluationUsageTracker usageTracker) {
	}

	/**
	 * LLM Judge 可空结果。
	 *
	 * @param relevancy   相关性分数
	 * @param factChecking 事实一致性分数
	 * @param error       失败原因
	 * @param promptTokens Judge 输入 Token
	 * @param completionTokens Judge 输出 Token
	 */
	private record JudgeScores(Float relevancy, Float factChecking, String error,
			int promptTokens, int completionTokens) {
	}

	/**
	 * 已知稳定文档的 fixture 和真实边界。
	 *
	 * @param fixtureId      报告使用的 fixture ID
	 * @param entCode        实际租户
	 * @param knowledgeBaseId 实际知识库
	 * @param boundaryType   边界说明
	 */
	private record DocumentScope(String fixtureId, String entCode, String knowledgeBaseId,
			String boundaryType) {
	}

	/**
	 * 成功导入文档的精确清理身份。
	 *
	 * @param entCode         租户编码
	 * @param knowledgeBaseId 知识库 ID
	 * @param documentId      稳定文档 ID
	 * @param version         文档版本
	 * @param sourceName      来源名
	 */
	private record ImportedDocument(String entCode, String knowledgeBaseId, String documentId,
			int version, String sourceName) {
	}

	/**
	 * 评测中引用与本轮证据比对使用的完整分片身份。
	 *
	 * @param knowledgeBaseId 知识库 ID
	 * @param documentId      稳定文档 ID
	 * @param documentVersion 文档版本
	 * @param chunkId         分片 ID
	 * @param chunkIndex      分片顺序
	 */
	private record CitationEvidenceIdentity(String knowledgeBaseId, String documentId,
			String documentVersion, String chunkId, String chunkIndex) {
	}

}
