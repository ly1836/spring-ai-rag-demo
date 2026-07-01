package com.example.rag.chat;

import java.util.List;
import java.util.Map;

import com.example.rag.billing.BillingService;
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
	 * 验证 knowledge 模式继续传递 conversationId，且不会向模型暴露 ERP tools。
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
			eq("deepseek-chat"), anyInt(), anyInt(), anyInt(), any(), anyInt(), anyInt(), any()))
			.thenReturn("assistant-msg");
		ErpAssistantService service = new ErpAssistantService(ChatClient.builder(chatModel), modelRegistry,
			vectorStore, historyService, billingService, chatMemoryRepository, toolRegistryService, toolCallRecorder,
			toolCallLogService);
		TenantContext.setEntCode("ENT001");

		String answer = service.askKnowledge("当前问题", "c1", "deepseek-chat", true);

		assertThat(answer).isEqualTo("模型回答");
		assertThat(chatModel.lastPrompt).isNotNull();
		assertThat(chatModel.lastPrompt.getOptions()).isInstanceOf(ToolCallingChatOptions.class);
		ToolCallingChatOptions options = (ToolCallingChatOptions) chatModel.lastPrompt.getOptions();
		assertThat(options.getModel()).isEqualTo("deepseek-chat");
		assertThat(options.getToolCallbacks()).isNullOrEmpty();
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
			eq("deepseek-chat"), anyInt(), anyInt(), anyInt(), any(), anyInt(), anyInt(), any()))
			.thenReturn("assistant-msg");
		ErpAssistantService service = new ErpAssistantService(ChatClient.builder(chatModel), modelRegistry,
			vectorStore, historyService, billingService, chatMemoryRepository, toolRegistryService, toolCallRecorder,
			toolCallLogService);
		TenantContext.setEntCode("ENT001");

		service.askData("查订单", "c1", "deepseek-chat", false);
		service.askData("查订单", "c1", "deepseek-chat", false);

		Map<String, ChatClient> cache =
			(Map<String, ChatClient>) ReflectionTestUtils.getField(service, "providerClientCache");
		assertThat(cache).containsOnlyKeys("deepseek:2");
	}

	/**
	 * 验证系统提示仅保留动态 Tool 的通用优先级，不绑定具体业务 Tool。
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
			eq("deepseek-chat"), anyInt(), anyInt(), anyInt(), any(), anyInt(), anyInt(), any()))
			.thenReturn("assistant-msg");
		ErpAssistantService service = new ErpAssistantService(ChatClient.builder(chatModel), modelRegistry,
			vectorStore, historyService, billingService, chatMemoryRepository, toolRegistryService, toolCallRecorder,
			toolCallLogService);
		TenantContext.setEntCode("ENT001");

		service.askData("按客户名称为张三电子科技有限公司查询销售订单列表", "c1", "deepseek-chat", false);

		assertThat(chatModel.lastPrompt.getInstructions().toString())
			.contains("当动态数据库 Tool 与代码内置 Tool 能力重叠时，优先选择动态数据库 Tool")
			.doesNotContain("query_dynamic_sales_orders")
			.doesNotContain("getRecentSalesOrders")
			.doesNotContain("getSalesOrders 是代码内置兼容工具");
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

		/**
		 * 返回固定模型响应，并记录最终 Prompt。
		 */
		@Override
		public ChatResponse call(Prompt prompt) {
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

}
