package com.example.rag.knowledge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.example.rag.config.GlobalExceptionHandler;
import com.example.rag.controller.KnowledgeBaseController;
import com.example.rag.vo.KnowledgeVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 知识库管理控制器测试。
 */
class KnowledgeBaseControllerTest {

	private KnowledgeBaseService knowledgeBaseService;

	private KnowledgeDocumentService knowledgeDocumentService;

	private KnowledgeDocumentIngestionService ingestionService;

	private MockMvc mockMvc;

	/**
	 * 初始化控制器及全局异常处理器。
	 */
	@BeforeEach
	public void setUp() {
		this.knowledgeBaseService = mock(KnowledgeBaseService.class);
		this.knowledgeDocumentService = mock(KnowledgeDocumentService.class);
		this.ingestionService = mock(KnowledgeDocumentIngestionService.class);
		this.mockMvc = MockMvcBuilders.standaloneSetup(new KnowledgeBaseController(
			this.knowledgeBaseService, this.knowledgeDocumentService, this.ingestionService))
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	/**
	 * 验证知识库列表使用统一响应结构。
	 */
	@Test
	public void shouldListKnowledgeBases() throws Exception {
		when(this.knowledgeBaseService.listKnowledgeBases()).thenReturn(List.of(
			new KnowledgeVO.KnowledgeBaseItem("kb-1", "默认知识库", "说明",
				"active", true, 1, "2026-09-07 10:00:00", "2026-09-07 10:00:00")));

		this.mockMvc.perform(get("/api/knowledge-bases"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.data[0].knowledgeBaseId").value("kb-1"))
			.andExpect(jsonPath("$.data.data[0].documentCount").value(1));
	}

	/**
	 * 验证 JSON 创建请求绑定到知识库服务。
	 */
	@Test
	public void shouldCreateKnowledgeBaseFromJson() throws Exception {
		when(this.knowledgeBaseService.createKnowledgeBase(any())).thenReturn(
			new KnowledgeVO.KnowledgeBaseItem("kb-2", "售后手册", "说明",
				"active", false, 0, "", ""));

		this.mockMvc.perform(post("/api/knowledge-bases")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"售后手册\",\"description\":\"说明\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.knowledgeBaseId").value("kb-2"));

		verify(this.knowledgeBaseService).createKnowledgeBase(
			new KnowledgeVO.CreateKnowledgeBaseRequest("售后手册", "说明"));
	}

	/**
	 * 验证 multipart 文档上传路径和响应结构。
	 */
	@Test
	public void shouldUploadKnowledgeDocument() throws Exception {
		MockMultipartFile file = new MockMultipartFile(
			"file", "manual.txt", "text/plain", "测试文档".getBytes());
		when(this.ingestionService.importFile(anyString(), isNull(), any(), anyString(),
			anyString(), anyLong())).thenReturn(new KnowledgeVO.KnowledgeDocumentImportResponse(
				"doc-1", "kb-1", "manual.txt", 1, "ready", 2));

		this.mockMvc.perform(multipart("/api/knowledge-bases/kb-1/documents").file(file))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.documentId").value("doc-1"))
			.andExpect(jsonPath("$.data.status").value("ready"));
	}

	/**
	 * 验证参数异常映射为 PARAM_ERROR。
	 */
	@Test
	public void shouldMapParameterError() throws Exception {
		when(this.knowledgeBaseService.createKnowledgeBase(any()))
			.thenThrow(new IllegalArgumentException("知识库名称不能为空"));

		this.mockMvc.perform(post("/api/knowledge-bases")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.errCode").value("PARAM_ERROR"));
	}

	/**
	 * 验证跨租户或不存在对象映射为 BIZ_ERROR。
	 */
	@Test
	public void shouldMapBusinessError() throws Exception {
		when(this.knowledgeDocumentService.listDocuments("other-base"))
			.thenThrow(new IllegalStateException("知识库不存在"));

		this.mockMvc.perform(get("/api/knowledge-bases/other-base/documents"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.errCode").value("BIZ_ERROR"));
	}

	/**
	 * 验证未预期基础设施异常映射为 SYSTEM_ERROR。
	 */
	@Test
	public void shouldMapSystemError() throws Exception {
		when(this.knowledgeBaseService.listKnowledgeBases())
			.thenThrow(new RuntimeException("模拟数据库异常"));

		this.mockMvc.perform(get("/api/knowledge-bases"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.errCode").value("SYSTEM_ERROR"));
	}

	/**
	 * 验证文档向量基础设施异常稳定映射为 SYSTEM_ERROR，且不返回内部原因。
	 */
	@Test
	public void shouldMapKnowledgeInfrastructureError() throws Exception {
		MockMultipartFile file = new MockMultipartFile(
			"file", "manual.txt", "text/plain", "测试文档".getBytes());
		when(this.ingestionService.importFile(anyString(), isNull(), any(), anyString(),
			anyString(), anyLong())).thenThrow(new KnowledgeInfrastructureException(
				"知识文档导入失败，请稍后重试", new IllegalStateException("向量连接口令错误")));

		this.mockMvc.perform(multipart("/api/knowledge-bases/kb-1/documents").file(file))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.errCode").value("SYSTEM_ERROR"))
			.andExpect(jsonPath("$.errMsg").value("系统内部错误，请稍后重试"));
	}

	/**
	 * 验证上传大小限制异常统一映射为 PARAM_ERROR。
	 */
	@Test
	public void shouldMapUploadSizeLimitToParameterError() throws Exception {
		MockMultipartFile file = new MockMultipartFile(
			"file", "manual.txt", "text/plain", "测试文档".getBytes());
		when(this.ingestionService.importFile(anyString(), isNull(), any(), anyString(),
			anyString(), anyLong())).thenThrow(new MaxUploadSizeExceededException(500L * 1024 * 1024));

		this.mockMvc.perform(multipart("/api/knowledge-bases/kb-1/documents").file(file))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.errCode").value("PARAM_ERROR"));
	}

	/**
	 * 验证应用保留 500MB 单文件和 550MB 整次请求大小边界。
	 *
	 * @throws Exception 读取应用配置失败时抛出
	 */
	@Test
	public void shouldKeepMultipartUploadSizeLimits() throws Exception {
		String applicationYaml = Files.readString(Path.of("src/main/resources/application.yml"));

		assertThat(applicationYaml)
			.contains("max-file-size: 500MB")
			.contains("max-request-size: 550MB");
	}

}
