package com.example.rag.chat;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.rag.billing.BillingService;
import com.example.rag.chat.chart.capture.ToolResultRecorder;
import com.example.rag.chat.chart.compile.ChartCompiler;
import com.example.rag.chat.chart.compile.ChartPlanFactory;
import com.example.rag.chat.chart.compile.ChartPlanValidator;
import com.example.rag.chat.chart.protocol.ChartSpecCodec;
import com.example.rag.chat.chart.selection.ChartSelectionService;
import com.example.rag.chat.chart.tool.ChartPlanToolCallback;
import com.example.rag.chat.client.AssistantClientProvider;
import com.example.rag.chat.dto.ChatAnswerResult;
import com.example.rag.chat.guard.BusinessDataTurnGuard;
import com.example.rag.chat.lifecycle.AssistantLifecycleService;
import com.example.rag.chat.output.AssistantAnswerSanitizer;
import com.example.rag.config.ModelProperties.ModelItem;
import com.example.rag.config.TenantContext;
import com.example.rag.conversation.ChatHistoryService;
import com.example.rag.tool.BaseTool;
import com.example.rag.tool.registry.ToolRegistryService;
import com.example.rag.tool.registry.ToolSnapshot;
import com.example.rag.tool.trace.ToolCallLogService;
import com.example.rag.tool.trace.ToolCallRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ERP 智能助手服务的 Spring AI 2 兼容测试。
 */
class ErpAssistantServiceTest {

	/**
	 * 清理测试写入的租户上下文。
	 */
	@AfterEach
	public void tearDown() {
		TenantContext.clear();
	}

	/**
	 * 验证 knowledge 模式传递 conversationId，使用知识专用提示词和召回参数，且不暴露 ERP tools。
	 */
	@Test
	public void shouldPassConversationIdAndDisableToolsInKnowledgeMode() {
		CapturingChatModel chatModel = new CapturingChatModel();
		ModelRegistry modelRegistry = mock(ModelRegistry.class);
		ModelItem defaultItem = model("deepseek-chat", "deepseek", "deepseek-chat", true);
		when(modelRegistry.getModelItem("deepseek-chat")).thenReturn(defaultItem);
		when(modelRegistry.getModelItem(null)).thenReturn(defaultItem);
		when(modelRegistry.getChatModel("deepseek-chat")).thenReturn(chatModel);
		when(modelRegistry.getDefaultModelName()).thenReturn("deepseek-chat");
		VectorStore vectorStore = mock(VectorStore.class);
		when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
		ChatHistoryService historyService = mock(ChatHistoryService.class);
		BillingService billingService = mock(BillingService.class);
		ChatMemoryRepository chatMemoryRepository = mock(ChatMemoryRepository.class);
		ToolRegistryService toolRegistryService = mock(ToolRegistryService.class);
		ToolCallRecorder toolCallRecorder = new ToolCallRecorder();
		ToolCallLogService toolCallLogService = mock(ToolCallLogService.class);
		when(chatMemoryRepository.findByConversationId("c1")).thenReturn(List.of(
			new UserMessage("上一问"),
			new AssistantMessage("上一答")));
		when(historyService.saveAssistantMessageAndUpdateStats(eq("c1"), anyString(), eq("knowledge"),
			eq("deepseek-chat"), anyInt(), anyInt(), anyInt(), any(), anyInt(),
			nullable(String.class), anyInt(), any(), eq("success"), nullable(String.class)))
			.thenReturn("assistant-msg");
		ErpAssistantService service = buildService(ChatClient.builder(chatModel), modelRegistry,
			vectorStore, historyService, billingService, chatMemoryRepository, toolRegistryService, toolCallRecorder,
			toolCallLogService);
		TenantContext.setEntCode("ENT001");

		ChatAnswerResult answer =
			service.askKnowledge("当前问题", "c1", "deepseek-chat", true);

		assertThat(answer.answer()).isEqualTo("模型回答");
		assertThat(answer.chart()).isNull();
		assertThat(chatModel.lastPrompt).isNotNull();
		assertThat(chatModel.lastPrompt.getOptions()).isInstanceOf(ToolCallingChatOptions.class);
		ToolCallingChatOptions options = (ToolCallingChatOptions) chatModel.lastPrompt.getOptions();
		assertThat(options.getModel()).isEqualTo("deepseek-chat");
		assertThat(options.getToolCallbacks()).isNullOrEmpty();
		assertThat(chatModel.lastPrompt.getInstructions().toString())
			.contains("知识库问答助手")
			.contains("不受制造业 ERP 业务范围限制")
			.contains("禁止以“超出 ERP 业务范围”等理由拒绝")
			.doesNotContain("你是一个制造业ERP系统的智能助手");
		ArgumentCaptor<SearchRequest> searchRequestCaptor = ArgumentCaptor.forClass(SearchRequest.class);
		verify(vectorStore).similaritySearch(searchRequestCaptor.capture());
		assertThat(searchRequestCaptor.getValue().getTopK()).isEqualTo(8);
		assertThat(searchRequestCaptor.getValue().getSimilarityThreshold()).isEqualTo(0.25);
		assertThat(searchRequestCaptor.getValue().hasFilterExpression()).isTrue();
		verify(chatMemoryRepository, atLeastOnce()).findByConversationId("c1");
		verify(historyService).initConversationAndSaveUserMessage("c1", "当前问题", "knowledge");
		verify(toolCallLogService).attachMessageId(anyString(), eq("assistant-msg"));
		verify(billingService).checkQuota();
	}

	/**
	 * 验证 Tool 快照刷新后只保留当前版本的带 Tool ChatClient 缓存。
	 */
	@Test
	@SuppressWarnings("unchecked")
	public void shouldKeepOnlyCurrentToolVersionClientCache() {
		CapturingChatModel chatModel = new CapturingChatModel();
		ModelRegistry modelRegistry = mock(ModelRegistry.class);
		ModelItem defaultItem = model("deepseek-chat", "deepseek", "deepseek-chat", true);
		when(modelRegistry.getModelItem("deepseek-chat")).thenReturn(defaultItem);
		when(modelRegistry.getModelItem(null)).thenReturn(defaultItem);
		when(modelRegistry.getChatModel("deepseek-chat")).thenReturn(chatModel);
		when(modelRegistry.getDefaultModelName()).thenReturn("deepseek-chat");
		VectorStore vectorStore = mock(VectorStore.class);
		ChatHistoryService historyService = mock(ChatHistoryService.class);
		BillingService billingService = mock(BillingService.class);
		ChatMemoryRepository chatMemoryRepository = mock(ChatMemoryRepository.class);
		ToolRegistryService toolRegistryService = mock(ToolRegistryService.class);
		when(toolRegistryService.currentSnapshot()).thenReturn(
			new ToolSnapshot(1L, List.of(), List.of()),
			new ToolSnapshot(2L, List.of(), List.of()));
		ToolCallRecorder toolCallRecorder = new ToolCallRecorder();
		ToolCallLogService toolCallLogService = mock(ToolCallLogService.class);
		when(historyService.saveAssistantMessageAndUpdateStats(eq("c1"), anyString(), eq("data"),
			eq("deepseek-chat"), anyInt(), anyInt(), anyInt(), any(), anyInt(),
			nullable(String.class), anyInt(), any(), eq("success"), nullable(String.class)))
			.thenReturn("assistant-msg");
		ErpAssistantService service = buildService(ChatClient.builder(chatModel), modelRegistry,
			vectorStore, historyService, billingService, chatMemoryRepository, toolRegistryService, toolCallRecorder,
			toolCallLogService);
		TenantContext.setEntCode("ENT001");

		service.askData("查订单", "c1", "deepseek-chat", false);
		service.askData("查订单", "c1", "deepseek-chat", false);

		AssistantClientProvider clientProvider =
			(AssistantClientProvider) ReflectionTestUtils.getField(service, "clientProvider");
		Map<String, ChatClient> cache =
			(Map<String, ChatClient>) ReflectionTestUtils.getField(clientProvider, "providerClientCache");
		assertThat(cache).containsOnlyKeys("deepseek:2");
	}

	/**
	 * 验证系统提示包含动态 Tool 优先级、中文回复、内部标识保密和图表强制规划规则。
	 */
	@Test
	public void shouldPreferDynamicToolGenerallyInSystemPrompt() {
		CapturingChatModel chatModel = new CapturingChatModel();
		ModelRegistry modelRegistry = mock(ModelRegistry.class);
		ModelItem defaultItem = model("deepseek-chat", "deepseek", "deepseek-chat", true);
		when(modelRegistry.getModelItem("deepseek-chat")).thenReturn(defaultItem);
		when(modelRegistry.getModelItem(null)).thenReturn(defaultItem);
		when(modelRegistry.getChatModel("deepseek-chat")).thenReturn(chatModel);
		when(modelRegistry.getDefaultModelName()).thenReturn("deepseek-chat");
		VectorStore vectorStore = mock(VectorStore.class);
		ChatHistoryService historyService = mock(ChatHistoryService.class);
		BillingService billingService = mock(BillingService.class);
		ChatMemoryRepository chatMemoryRepository = mock(ChatMemoryRepository.class);
		ToolRegistryService toolRegistryService = mock(ToolRegistryService.class);
		when(toolRegistryService.currentSnapshot()).thenReturn(new ToolSnapshot(1L, List.of(), List.of()));
		ToolCallRecorder toolCallRecorder = new ToolCallRecorder();
		ToolCallLogService toolCallLogService = mock(ToolCallLogService.class);
		when(historyService.saveAssistantMessageAndUpdateStats(eq("c1"), anyString(), eq("data"),
			eq("deepseek-chat"), anyInt(), anyInt(), anyInt(), any(), anyInt(),
			nullable(String.class), anyInt(), any(), eq("success"), nullable(String.class)))
			.thenReturn("assistant-msg");
		ErpAssistantService service = buildService(ChatClient.builder(chatModel), modelRegistry,
			vectorStore, historyService, billingService, chatMemoryRepository, toolRegistryService, toolCallRecorder,
			toolCallLogService);
		TenantContext.setEntCode("ENT001");

		service.askData("按客户名称为张三电子科技有限公司查询销售订单列表", "c1", "deepseek-chat", false);

		assertThat(chatModel.lastPrompt.getInstructions().toString())
			.contains("能力重叠的工具不得重复查询")
			.contains("当动态数据库 Tool 与代码内置 Tool 能力重叠时，只调用动态数据库 Tool")
			.contains("用户当前问题包含中文时，最终回答必须全程使用中文")
			.contains("禁止在最终回答中出现 Tool 名称、函数名称、数据库表名、字段名或 SQL")
			.contains("返回两行及以上数据，且包含数值字段或可按状态、类型等分类计数时，必须判定为适合可视化")
			.contains("后端会按结构化记录数生成可信计数")
			.contains("即使最终回答已经使用 Markdown 表格，也不得省略图表规划")
			.contains("图表规划只填写图表类型和业务标题")
			.contains("字段绑定、数据转换和安全展示选项全部由后端")
			.contains("只有本轮全部结构化业务数据都无法满足所选图表类型时才降级为文本")
			.contains("禁止向用户描述图表规划、自动绑定或降级过程")
			.contains("业务查询和图表规划阶段只能调用 Tool，不得输出任何过程说明、分析或自言自语")
			.contains("最终回答不得宣称图表已经生成、展示或渲染")
			.contains("最终回答必须以 <!--FINAL_ANSWER--> 开头")
			.doesNotContain("query_dynamic_sales_orders")
			.doesNotContain("getRecentSalesOrders")
			.doesNotContain("getSalesOrders 是代码内置兼容工具");
	}

	/**
	 * 验证长会话存在历史 Markdown 表格时，当前业务数据问题仍执行一次有界重试。
	 */
	@Test
	public void shouldRetryCurrentBusinessTurnInsteadOfReusingHistoricalTable() {
		CapturingChatModel chatModel = new CapturingChatModel();
		ModelRegistry modelRegistry = mock(ModelRegistry.class);
		ModelItem defaultItem = model("deepseek-chat", "deepseek", "deepseek-chat", true);
		when(modelRegistry.getModelItem("deepseek-chat")).thenReturn(defaultItem);
		when(modelRegistry.getModelItem(null)).thenReturn(defaultItem);
		when(modelRegistry.getChatModel("deepseek-chat")).thenReturn(chatModel);
		when(modelRegistry.getDefaultModelName()).thenReturn("deepseek-chat");
		VectorStore vectorStore = mock(VectorStore.class);
		when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
		ChatHistoryService historyService = mock(ChatHistoryService.class);
		when(historyService.saveAssistantMessageAndUpdateStats(eq("c1"), anyString(), eq("auto"),
			eq("deepseek-chat"), anyInt(), anyInt(), anyInt(), any(), anyInt(),
			nullable(String.class), anyInt(), any(), eq("success"), nullable(String.class)))
			.thenReturn("assistant-msg");
		ChatMemoryRepository memoryRepository = mock(ChatMemoryRepository.class);
		when(memoryRepository.findByConversationId("c1")).thenReturn(List.of(
			new UserMessage("上一轮售后工单"),
			new AssistantMessage("| 状态 | 数量 |\n|---|---|\n| 已解决 | 3 |")));
		ToolRegistryService toolRegistryService = mock(ToolRegistryService.class);
		when(toolRegistryService.currentSnapshot()).thenReturn(new ToolSnapshot(1L, List.of(), List.of()));
		ErpAssistantService service = buildService(ChatClient.builder(chatModel), modelRegistry,
			vectorStore, historyService, mock(BillingService.class), memoryRepository,
			toolRegistryService, new ToolCallRecorder(), mock(ToolCallLogService.class));
		TenantContext.setEntCode("ENT001");

		service.ask("再按状态统计近期售后工单", "c1", "deepseek-chat", true);

		assertThat(chatModel.callCount).isEqualTo(2);
		assertThat(chatModel.lastPrompt.getInstructions().toString())
			.contains("上一次回答没有取得本轮业务查询结果")
			.contains("再按状态统计近期售后工单");
		verify(memoryRepository, atLeastOnce()).findByConversationId("c1");
	}

	/**
	 * 构造测试用模型配置项。
	 */
	private static ModelItem model(String id, String provider, String modelName, boolean isDefault) {
		ModelItem item = new ModelItem();
		item.setId(id);
		item.setProvider(provider);
		item.setModelName(modelName);
		item.setDefault(isDefault);
		return item;
	}

	/**
	 * 捕获 ChatClient 最终传给 ChatModel 的 Prompt。
	 */
	private static final class CapturingChatModel implements ChatModel {

		/** 最近一次模型请求。 */
		private Prompt lastPrompt;

		/** 模型调用次数。 */
		private int callCount;

		/**
		 * 返回固定模型响应，并记录最终 Prompt。
		 */
		@Override
		public ChatResponse call(Prompt prompt) {
			this.callCount++;
			this.lastPrompt = prompt;
			return new ChatResponse(List.of(new Generation(new AssistantMessage("模型回答"))));
		}

		/**
		 * 使用支持 ToolCalling 的 options，便于验证 tools 是否被写入请求。
		 */
		@Override
		public ChatOptions getOptions() {
			return DefaultToolCallingChatOptions.builder().build();
		}
	}

	/**
	 * 测试用 ERP Tool，确保默认客户端会注册可用工具。
	 */
	private static final class TestTool extends BaseTool {

		/**
		 * 注入空 JdbcTemplate，仅用于通过 BaseTool 构造要求。
		 */
		private TestTool() {
			super(mock(JdbcTemplate.class));
		}

		/**
		 * 提供一个可被 Spring AI 识别的测试工具方法。
		 */
		@Tool(description = "查询测试数据")
		public String queryTestData() {
			return "ok";
		}
	}

	/**
	 * 构造包含内部图表规划能力的智能助手服务。
	 *
	 * @param builder              ChatClient 构建器
	 * @param modelRegistry        模型注册中心
	 * @param vectorStore          向量库
	 * @param historyService       历史服务
	 * @param billingService       计费服务
	 * @param memoryRepository     会话记忆仓库
	 * @param toolRegistryService  Tool 注册服务
	 * @param toolCallRecorder     Tool 调用记录器
	 * @param toolCallLogService   Tool 调用日志服务
	 * @return 智能助手服务
	 */
	private ErpAssistantService buildService(ChatClient.Builder builder, ModelRegistry modelRegistry,
			VectorStore vectorStore, ChatHistoryService historyService, BillingService billingService,
			ChatMemoryRepository memoryRepository, ToolRegistryService toolRegistryService,
			ToolCallRecorder toolCallRecorder, ToolCallLogService toolCallLogService) {
		ToolResultRecorder toolResultRecorder = new ToolResultRecorder();
		ChartSpecCodec codec = new ChartSpecCodec();
		ChartPlanValidator validator = new ChartPlanValidator();
		ChartPlanToolCallback planTool = new ChartPlanToolCallback(
			new ChartCompiler(validator, codec), toolResultRecorder,
			new ChartPlanFactory(validator));
		AssistantClientProvider clientProvider = new AssistantClientProvider(
			builder, modelRegistry, toolRegistryService, planTool);
		ChartSelectionService chartSelectionService = new ChartSelectionService(
			clientProvider, planTool, toolResultRecorder);
		AssistantLifecycleService lifecycleService = new AssistantLifecycleService(
			historyService, billingService, toolCallRecorder, toolCallLogService,
			toolResultRecorder, codec, new AssistantAnswerSanitizer(), chartSelectionService);
		return new ErpAssistantService(
			clientProvider, lifecycleService, vectorStore, memoryRepository, toolRegistryService,
			new BusinessDataTurnGuard(toolResultRecorder, toolCallRecorder));
	}

	/**
	 * 验证不同 Provider 都装配同一份内部图表规划 Schema，且该 Tool 不进入业务 Tool 快照。
	 */
	@Test
	public void shouldUseSameChartPlanningSchemaAcrossProviders() {
		Set<String> planningSchemas = new LinkedHashSet<>();
		for (ModelItem item : List.of(
				model("deepseek-chat", "deepseek", "deepseek-chat", true),
				model("qwen-max", "openai", "qwen-max", false),
				model("gemini-flash", "google-genai", "gemini-2.5-flash", false))) {
			CapturingChatModel chatModel = new CapturingChatModel();
			ModelRegistry modelRegistry = mock(ModelRegistry.class);
			ModelItem defaultItem = model("deepseek-chat", "deepseek", "deepseek-chat", true);
			when(modelRegistry.getModelItem(item.getId())).thenReturn(item);
			when(modelRegistry.getModelItem(null)).thenReturn(defaultItem);
			when(modelRegistry.getChatModel(item.getId())).thenReturn(chatModel);
			when(modelRegistry.getDefaultModelName()).thenReturn(defaultItem.getModelName());
			ChatHistoryService historyService = mock(ChatHistoryService.class);
			when(historyService.saveAssistantMessageAndUpdateStats(eq("c1"), anyString(), eq("data"),
				eq(item.getModelName()), anyInt(), anyInt(), anyInt(), any(), anyInt(),
				nullable(String.class), anyInt(), any(), eq("success"), nullable(String.class)))
				.thenReturn("assistant-msg");
			ToolRegistryService toolRegistryService = mock(ToolRegistryService.class);
			ToolSnapshot businessSnapshot = new ToolSnapshot(1L, List.of(), List.of());
			when(toolRegistryService.currentSnapshot()).thenReturn(businessSnapshot);
			ErpAssistantService service = buildService(ChatClient.builder(chatModel), modelRegistry,
				mock(VectorStore.class), historyService, mock(BillingService.class),
				mock(ChatMemoryRepository.class), toolRegistryService, new ToolCallRecorder(),
				mock(ToolCallLogService.class));
			TenantContext.setEntCode("ENT001");

			service.askData("按月统计销售额", "c1", item.getId(), false);

			ToolCallingChatOptions options = (ToolCallingChatOptions) chatModel.lastPrompt.getOptions();
			assertThat(options.getToolCallbacks()).hasSize(1);
			assertThat(options.getToolCallbacks().get(0).getToolDefinition().name())
				.isEqualTo(ChartPlanToolCallback.TOOL_NAME);
			planningSchemas.add(options.getToolCallbacks().get(0).getToolDefinition().inputSchema());
			assertThat(businessSnapshot.callbacks()).isEmpty();
			TenantContext.clear();
		}

		assertThat(planningSchemas).hasSize(1);
	}

}
