package com.example.rag.chat;

import java.util.List;
import java.util.Map;

import com.example.rag.chat.dto.ChatAnswerResult;
import com.example.rag.chat.dto.ChatStreamFrame;
import com.example.rag.chat.dto.DocSnippet;
import com.example.rag.controller.ChatController;
import com.example.rag.config.ModelProperties;
import com.example.rag.config.ModelProperties.ModelItem;
import com.example.rag.vo.ChatVO;
import com.example.rag.vo.ChartVO;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 聊天控制器接口形态测试。
 */
class ChatControllerTest {

	/**
	 * 验证模型列表接口返回 RespVO 包装和配置中的模型列表。
	 */
	@Test
	public void shouldReturnConfiguredModelsFromModelsApi() throws Exception {
		MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
			new ChatController(mock(ErpAssistantService.class), mock(DocumentLoaderService.class), modelProperties()))
			.build();

		mockMvc.perform(get("/api/models"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.models[0].id").value("deepseek-chat"))
			.andExpect(jsonPath("$.data.models[0].label").value("DeepSeek Chat"))
			.andExpect(jsonPath("$.data.models[0].modelName").value("deepseek-chat"))
			.andExpect(jsonPath("$.data.models[0].isDefault").value(true))
			.andExpect(jsonPath("$.data.models[1].id").value("qwen-max"))
			.andExpect(jsonPath("$.data.models[1].isDefault").value(false));
	}

	/**
	 * 验证非流式问答接口仍返回 RespVO 包装，并按 mode 路由到 knowledge 问答。
	 */
	@Test
	public void shouldReturnRespVoForNonStreamingAskApi() throws Exception {
		ErpAssistantService assistantService = mock(ErpAssistantService.class);
		when(assistantService.askKnowledge("查手册", "c1", "deepseek-chat", true))
			.thenReturn(new ChatAnswerResult("知识库回答", null));
		MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
			new ChatController(assistantService, mock(DocumentLoaderService.class), modelProperties()))
			.build();

		mockMvc.perform(get("/api/ask")
				.param("question", "查手册")
				.param("conversationId", "c1")
				.param("mode", "knowledge")
				.param("modelId", "deepseek-chat"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.conversationId").value("c1"))
			.andExpect(jsonPath("$.data.question").value("查手册"))
			.andExpect(jsonPath("$.data.answer").value("知识库回答"))
			.andExpect(jsonPath("$.data.mode").value("knowledge"));
		verify(assistantService).askKnowledge("查手册", "c1", "deepseek-chat", true);
	}

	/**
	 * 验证文档搜索接口保持 RespVO 包装和搜索结果结构。
	 */
	@Test
	public void shouldReturnRespVoForSearchApi() throws Exception {
		ErpAssistantService assistantService = mock(ErpAssistantService.class);
		when(assistantService.searchDocs("说明书", 3)).thenReturn(List.of(
			new DocSnippet("片段内容", "manual.pdf", 0.91)));
		MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
			new ChatController(assistantService, mock(DocumentLoaderService.class), modelProperties()))
			.build();

		mockMvc.perform(get("/api/search")
				.param("query", "说明书")
				.param("topK", "3"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.query").value("说明书"))
			.andExpect(jsonPath("$.data.results[0].text").value("片段内容"))
			.andExpect(jsonPath("$.data.results[0].source").value("manual.pdf"))
			.andExpect(jsonPath("$.data.results[0].score").value(0.91));
		verify(assistantService).searchDocs("说明书", 3);
	}

	/**
	 * 构造测试用模型配置。
	 */
	private static ModelProperties modelProperties() {
		ModelProperties properties = new ModelProperties();
		properties.setModels(List.of(
			model("deepseek-chat", "DeepSeek Chat", "deepseek", "deepseek-chat", true),
			model("qwen-max", "通义千问 Max", "openai", "qwen-max", false)));
		return properties;
	}

	/**
	 * 构造测试用模型配置项。
	 */
	private static ModelItem model(String id, String label, String provider, String modelName, boolean isDefault) {
		ModelItem item = new ModelItem();
		item.setId(id);
		item.setLabel(label);
		item.setProvider(provider);
		item.setModelName(modelName);
		item.setDefault(isDefault);
		return item;
	}

	/**
	 * 验证统一流式问答会映射为带显式事件名的 SSE。
	 */
	@Test
	@SuppressWarnings("unchecked")
	public void shouldReturnTypedServerSentEvents() {
		ErpAssistantService assistantService = mock(ErpAssistantService.class);
		ChartVO.ChartSpec chart = new ChartVO.ChartSpec(
			ChartVO.SCHEMA_VERSION, "chart-1", ChartVO.ChartType.BAR, "销售额", null,
			new ChartVO.Dataset(
				List.of(new ChartVO.Dimension("name", "名称", "string", null)),
				List.of(Map.of("name", "A"))),
			Map.of("category", List.of("name")), null,
			new ChartVO.ChartSource(List.of("query_sales")));
		when(assistantService.askDataStream("查订单", "c2", "qwen-max", true))
			.thenReturn(Flux.just(
				new ChatStreamFrame("delta", new ChatVO.StreamDelta("第一行\n第二行")),
				new ChatStreamFrame("chart", new ChatVO.StreamChart(chart)),
				new ChatStreamFrame("done", new ChatVO.StreamDone("c2", "success"))));
		ChatController controller = new ChatController(
			assistantService, mock(DocumentLoaderService.class), modelProperties());

		ResponseEntity<?> response = controller.askStream(
			new ChatVO.AskRequest("查订单", "c2", "data", "qwen-max"));
		List<ServerSentEvent<Object>> events =
			((Flux<ServerSentEvent<Object>>) response.getBody()).collectList().block();

		assertThat(events).extracting(ServerSentEvent::event)
			.containsExactly("delta", "chart", "done");
		assertThat(((ChatVO.StreamDelta) events.get(0).data()).text()).isEqualTo("第一行\n第二行");
		verify(assistantService).askDataStream("查订单", "c2", "qwen-max", true);
	}

	/**
	 * 验证非流式问答在存在图表时通过统一响应结构返回 ChartSpec。
	 */
	@Test
	public void shouldReturnChartForNonStreamingAskApi() throws Exception {
		ErpAssistantService assistantService = mock(ErpAssistantService.class);
		ChartVO.ChartSpec chart = new ChartVO.ChartSpec(
			ChartVO.SCHEMA_VERSION, "chart-1", ChartVO.ChartType.BAR, "销售额", "元",
			new ChartVO.Dataset(
				List.of(
					new ChartVO.Dimension("name", "月份", "string", null),
					new ChartVO.Dimension("value", "销售额", "number", "元")),
				List.of(Map.of("name", "一月", "value", 100))),
			Map.of("category", List.of("name"), "value", List.of("value")), null,
			new ChartVO.ChartSource(List.of("query_sales")));
		when(assistantService.askData("按月统计销售额", "c1", "deepseek-chat", true))
			.thenReturn(new ChatAnswerResult("查询完成", chart));
		MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
			new ChatController(assistantService, mock(DocumentLoaderService.class), modelProperties()))
			.build();

		mockMvc.perform(get("/api/ask")
				.param("question", "按月统计销售额")
				.param("conversationId", "c1")
				.param("mode", "data")
				.param("modelId", "deepseek-chat"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.answer").value("查询完成"))
			.andExpect(jsonPath("$.data.chart.schemaVersion").value(ChartVO.SCHEMA_VERSION))
			.andExpect(jsonPath("$.data.chart.type").value("bar"))
			.andExpect(jsonPath("$.data.chart.dataset.rows[0].value").value(100));
	}

}
