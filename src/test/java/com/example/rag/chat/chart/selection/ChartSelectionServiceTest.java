package com.example.rag.chat.chart.selection;

import java.util.List;

import com.example.rag.chat.ModelRegistry;
import com.example.rag.chat.chart.capture.ToolResultRecorder;
import com.example.rag.chat.chart.compile.ChartCompiler;
import com.example.rag.chat.chart.compile.ChartPlanFactory;
import com.example.rag.chat.chart.compile.ChartPlanValidator;
import com.example.rag.chat.chart.protocol.ChartSpecCodec;
import com.example.rag.chat.chart.tool.ChartPlanToolCallback;
import com.example.rag.chat.client.AssistantClientProvider;
import com.example.rag.config.ModelProperties.ModelItem;
import com.example.rag.config.TenantContext;
import com.example.rag.tool.registry.ToolRegistryService;
import com.example.rag.vo.ChartVO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 图表类型和标题兜底选择服务测试。
 */
class ChartSelectionServiceTest {

	/**
	 * 清理测试租户上下文。
	 */
	@AfterEach
	public void tearDown() {
		TenantContext.clear();
	}

	/**
	 * 验证已有结构化业务数据但主回答漏规划时，由当前模型只补选类型和标题并生成图表。
	 */
	@Test
	public void shouldGenerateChartFromModelTypeAndTitleSelection() {
		ToolResultRecorder recorder = new ToolResultRecorder();
		ChartPlanValidator validator = new ChartPlanValidator();
		ChartPlanToolCallback callback = new ChartPlanToolCallback(
			new ChartCompiler(validator, new ChartSpecCodec()), recorder,
			new ChartPlanFactory(validator));
		FixedSelectionChatModel chatModel = new FixedSelectionChatModel();
		AssistantClientProvider clientProvider = this.buildClientProvider(chatModel, callback);
		ChartSelectionService service = new ChartSelectionService(clientProvider, callback, recorder);
		recorder.capture("t1", "ENT001", "c1", "query_after_sales", "database",
			"[{\"status\":\"已解决\",\"count\":3},{\"status\":\"待处理\",\"count\":1}]");
		TenantContext.setEntCode("ENT001");
		TenantContext.setUserId("U001");

		ChatResponse selectionResponse = service.ensureChart(
			"近期售后工单处理情况怎么样？", "已解决 3 张，待处理 1 张。",
			"model-1", "test-model", "t1", "c1", "data");

		ChartVO.ChartSpec chart = recorder.getChart("t1", "ENT001", "c1");
		assertThat(selectionResponse).isNotNull();
		assertThat(chart).isNotNull();
		assertThat(chart.type()).isEqualTo(ChartVO.ChartType.PIE);
		assertThat(chart.title()).isEqualTo("近期售后工单处理状态分布");
		assertThat(chatModel.callCount).isEqualTo(1);
	}

	/**
	 * 验证没有本轮结构化业务数据时不会额外调用图表选择模型。
	 */
	@Test
	public void shouldSkipSelectionWithoutCurrentBusinessData() {
		ToolResultRecorder recorder = new ToolResultRecorder();
		ChartPlanValidator validator = new ChartPlanValidator();
		ChartPlanToolCallback callback = new ChartPlanToolCallback(
			new ChartCompiler(validator, new ChartSpecCodec()), recorder,
			new ChartPlanFactory(validator));
		FixedSelectionChatModel chatModel = new FixedSelectionChatModel();
		ChartSelectionService service = new ChartSelectionService(
			this.buildClientProvider(chatModel, callback), callback, recorder);
		TenantContext.setEntCode("ENT001");

		ChatResponse selectionResponse = service.ensureChart(
			"近期售后工单处理情况怎么样？", "没有查询到数据。",
			"model-1", "test-model", "t1", "c1", "data");

		assertThat(selectionResponse).isNull();
		assertThat(chatModel.callCount).isZero();
	}

	/**
	 * 验证 Provider 在唯一选择对象外增加 JSON 代码围栏时仍能在首次补选生成图表。
	 */
	@Test
	public void shouldGenerateChartFromMarkdownFencedSelection() {
		ToolResultRecorder recorder = new ToolResultRecorder();
		ChartPlanValidator validator = new ChartPlanValidator();
		ChartPlanToolCallback callback = new ChartPlanToolCallback(
			new ChartCompiler(validator, new ChartSpecCodec()), recorder,
			new ChartPlanFactory(validator));
		FixedSelectionChatModel chatModel = new FixedSelectionChatModel("""
				```json
				{"type":"pie","title":"近期售后工单处理状态分布"}
				```
				""");
		ChartSelectionService service = new ChartSelectionService(
			this.buildClientProvider(chatModel, callback), callback, recorder);
		recorder.capture("t1", "ENT001", "c1", "query_after_sales", "database",
			"[{\"status\":\"已解决\",\"count\":3},{\"status\":\"待处理\",\"count\":1}]");
		TenantContext.setEntCode("ENT001");
		TenantContext.setUserId("U001");

		ChatResponse selectionResponse = service.ensureChart(
			"近期售后工单处理情况怎么样？", "已解决 3 张，待处理 1 张。",
			"model-1", "test-model", "t1", "c1", "data");

		assertThat(selectionResponse).isNotNull();
		assertThat(recorder.getChart("t1", "ENT001", "c1")).isNotNull();
		assertThat(chatModel.callCount).isEqualTo(1);
		assertThat(chatModel.lastPrompt.getOptions().getTemperature()).isZero();
	}

	/**
	 * 验证多个选择对象或 Schema 外字段不会绕过补选输入约束。
	 */
	@Test
	public void shouldRejectAmbiguousOrExtendedSelection() {
		List<String> invalidSelections = List.of(
			"{\"type\":\"pie\",\"title\":\"状态分布\"}\n{\"type\":\"bar\",\"title\":\"状态对比\"}",
			"{\"type\":\"pie\",\"title\":\"状态分布\",\"value\":3}",
			"{\"type\":\"pie\",\"title\":\"" + "超长标题".repeat(600) + "\"}");
		for (int index = 0; index < invalidSelections.size(); index++) {
			ToolResultRecorder recorder = new ToolResultRecorder();
			ChartPlanValidator validator = new ChartPlanValidator();
			ChartPlanToolCallback callback = new ChartPlanToolCallback(
				new ChartCompiler(validator, new ChartSpecCodec()), recorder,
				new ChartPlanFactory(validator));
			FixedSelectionChatModel chatModel = new FixedSelectionChatModel(invalidSelections.get(index));
			ChartSelectionService service = new ChartSelectionService(
				this.buildClientProvider(chatModel, callback), callback, recorder);
			String traceId = "t" + index;
			recorder.capture(traceId, "ENT001", "c1", "query_after_sales", "database",
				"[{\"status\":\"已解决\",\"count\":3},{\"status\":\"待处理\",\"count\":1}]");
			TenantContext.setEntCode("ENT001");
			TenantContext.setUserId("U001");

			ChatResponse selectionResponse = service.ensureChart(
				"近期售后工单处理情况怎么样？", "已解决 3 张，待处理 1 张。",
				"model-1", "test-model", traceId, "c1", "data");

			assertThat(selectionResponse).isNotNull();
			assertThat(recorder.getChart(traceId, "ENT001", "c1")).isNull();
			assertThat(chatModel.callCount).isEqualTo(1);
		}
	}

	/**
	 * 构造使用固定模型响应的 ChatClient 提供器。
	 *
	 * @param chatModel 图表选择测试模型
	 * @param callback  图表规划回调
	 * @return ChatClient 提供器
	 */
	private AssistantClientProvider buildClientProvider(ChatModel chatModel,
			ChartPlanToolCallback callback) {
		ModelRegistry modelRegistry = mock(ModelRegistry.class);
		ModelItem item = new ModelItem();
		item.setId("model-1");
		item.setProvider("test");
		item.setModelName("test-model");
		item.setDefault(true);
		when(modelRegistry.getModelItem("model-1")).thenReturn(item);
		when(modelRegistry.getModelItem(null)).thenReturn(item);
		when(modelRegistry.getChatModel("model-1")).thenReturn(chatModel);
		when(modelRegistry.getDefaultModelName()).thenReturn("test-model");
		return new AssistantClientProvider(
			ChatClient.builder(chatModel), modelRegistry, mock(ToolRegistryService.class), callback);
	}

	/**
	 * 只返回固定图表选择 JSON 的测试模型。
	 */
	private static final class FixedSelectionChatModel implements ChatModel {

		/** 模型调用次数。 */
		private int callCount;

		/** 模型返回的选择文本。 */
		private final String responseText;

		/** 最近一次模型提示。 */
		private Prompt lastPrompt;

		/**
		 * 创建返回默认合法选择的测试模型。
		 */
		private FixedSelectionChatModel() {
			this("{\"type\":\"pie\",\"title\":\"近期售后工单处理状态分布\"}");
		}

		/**
		 * 创建返回指定选择文本的测试模型。
		 *
		 * @param responseText 模型返回文本
		 */
		private FixedSelectionChatModel(String responseText) {
			this.responseText = responseText;
		}

		/**
		 * 返回固定的饼图类型和中文标题。
		 *
		 * @param prompt 模型提示词
		 * @return 固定结构化响应
		 */
		@Override
		public ChatResponse call(Prompt prompt) {
			this.callCount++;
			this.lastPrompt = prompt;
			return new ChatResponse(List.of(new Generation(new AssistantMessage(this.responseText))));
		}

		/**
		 * 返回支持结构化输出的默认模型选项。
		 *
		 * @return 默认 Tool Calling 选项
		 */
		@Override
		public ChatOptions getOptions() {
			return DefaultToolCallingChatOptions.builder().build();
		}
	}
}
