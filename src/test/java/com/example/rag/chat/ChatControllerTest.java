package com.example.rag.chat;

import java.util.List;

import com.example.rag.controller.ChatController;
import com.example.rag.config.ModelProperties;
import com.example.rag.config.ModelProperties.ModelItem;
import com.example.rag.vo.ChatVO;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
		when(assistantService.askKnowledge("查手册", "c1", "deepseek-chat", true)).thenReturn("知识库回答");
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
	 * 验证流式问答接口返回 SSE Flux，不包装为 RespVO。
	 */
	@Test
	public void shouldReturnFluxForStreamingAskApi() {
		ErpAssistantService assistantService = mock(ErpAssistantService.class);
		when(assistantService.askDataStream("查订单", "c2", "qwen-max", true)).thenReturn(Flux.just("片段"));
		ChatController controller = new ChatController(assistantService, mock(DocumentLoaderService.class), modelProperties());

		ResponseEntity<?> response = controller.askStream(
			new ChatVO.AskRequest("查订单", "c2", "data", "qwen-max"), null);

		assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
		assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_EVENT_STREAM);
		assertThat(response.getHeaders().getFirst("X-Conversation-Id")).isEqualTo("c2");
		assertThat(response.getBody()).isInstanceOf(Flux.class);
		verify(assistantService).askDataStream("查订单", "c2", "qwen-max", true);
	}

	/**
	 * 验证文档搜索接口保持 RespVO 包装和搜索结果结构。
	 */
	@Test
	public void shouldReturnRespVoForSearchApi() throws Exception {
		ErpAssistantService assistantService = mock(ErpAssistantService.class);
		when(assistantService.searchDocs("说明书", 3)).thenReturn(List.of(
			new ErpAssistantService.DocSnippet("片段内容", "manual.pdf", 0.91)));
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

}
