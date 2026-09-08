package com.example.rag.controller;

import java.io.IOException;

import com.example.rag.knowledge.KnowledgeBaseService;
import com.example.rag.knowledge.KnowledgeDocumentIngestionService;
import com.example.rag.knowledge.KnowledgeDocumentService;
import com.example.rag.vo.KnowledgeVO;
import com.example.rag.vo.RespVO;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 租户知识库与文档管理接口。
 */
@RestController
@RequestMapping("/api/knowledge-bases")
public class KnowledgeBaseController {

	private final KnowledgeBaseService knowledgeBaseService;

	private final KnowledgeDocumentService knowledgeDocumentService;

	private final KnowledgeDocumentIngestionService ingestionService;

	/**
	 * 创建知识库管理控制器。
	 *
	 * @param knowledgeBaseService     知识库服务
	 * @param knowledgeDocumentService 文档注册服务
	 * @param ingestionService         文档导入编排服务
	 */
	public KnowledgeBaseController(KnowledgeBaseService knowledgeBaseService,
			KnowledgeDocumentService knowledgeDocumentService,
			KnowledgeDocumentIngestionService ingestionService) {
		this.knowledgeBaseService = knowledgeBaseService;
		this.knowledgeDocumentService = knowledgeDocumentService;
		this.ingestionService = ingestionService;
	}

	/**
	 * 查询当前租户知识库列表。
	 *
	 * @return 知识库列表响应
	 */
	@GetMapping
	public RespVO<KnowledgeVO.KnowledgeBaseListResponse> listKnowledgeBases() {
		return RespVO.success(new KnowledgeVO.KnowledgeBaseListResponse(
			this.knowledgeBaseService.listKnowledgeBases()));
	}

	/**
	 * 创建知识库。
	 *
	 * @param request 创建请求
	 * @return 新知识库
	 */
	@PostMapping
	public RespVO<KnowledgeVO.KnowledgeBaseItem> createKnowledgeBase(
			@RequestBody KnowledgeVO.CreateKnowledgeBaseRequest request) {
		return RespVO.success(this.knowledgeBaseService.createKnowledgeBase(request));
	}

	/**
	 * 更新知识库。
	 *
	 * @param knowledgeBaseId 知识库 ID
	 * @param request         更新请求
	 * @return 更新后的知识库
	 */
	@PutMapping("/{knowledgeBaseId}")
	public RespVO<KnowledgeVO.KnowledgeBaseItem> updateKnowledgeBase(
			@PathVariable String knowledgeBaseId,
			@RequestBody KnowledgeVO.UpdateKnowledgeBaseRequest request) {
		return RespVO.success(this.knowledgeBaseService.updateKnowledgeBase(knowledgeBaseId, request));
	}

	/**
	 * 删除空的非默认知识库。
	 *
	 * @param knowledgeBaseId 知识库 ID
	 * @return 删除响应
	 */
	@DeleteMapping("/{knowledgeBaseId}")
	public RespVO<KnowledgeVO.DeleteKnowledgeBaseResponse> deleteKnowledgeBase(
			@PathVariable String knowledgeBaseId) {
		this.knowledgeBaseService.deleteKnowledgeBase(knowledgeBaseId);
		return RespVO.success(new KnowledgeVO.DeleteKnowledgeBaseResponse(knowledgeBaseId));
	}

	/**
	 * 查询知识库稳定文档列表。
	 *
	 * @param knowledgeBaseId 知识库 ID
	 * @return 文档列表响应
	 */
	@GetMapping("/{knowledgeBaseId}/documents")
	public RespVO<KnowledgeVO.KnowledgeDocumentListResponse> listDocuments(
			@PathVariable String knowledgeBaseId) {
		return RespVO.success(new KnowledgeVO.KnowledgeDocumentListResponse(knowledgeBaseId,
			this.knowledgeDocumentService.listDocuments(knowledgeBaseId)));
	}

	/**
	 * 上传新知识文档。
	 *
	 * @param knowledgeBaseId 知识库 ID
	 * @param file            上传文件
	 * @return 文档导入响应
	 * @throws IOException 读取上传流失败时抛出系统异常
	 */
	@PostMapping("/{knowledgeBaseId}/documents")
	public RespVO<KnowledgeVO.KnowledgeDocumentImportResponse> uploadDocument(
			@PathVariable String knowledgeBaseId, @RequestParam("file") MultipartFile file) throws IOException {
		return RespVO.success(this.ingestionService.importFile(knowledgeBaseId, null,
			file.getInputStream(), file.getOriginalFilename(), file.getContentType(), file.getSize()));
	}

	/**
	 * 替换稳定文档内容并创建下一版本。
	 *
	 * @param knowledgeBaseId 知识库 ID
	 * @param documentId      稳定文档 ID
	 * @param file            替换文件
	 * @return 文档导入响应
	 * @throws IOException 读取上传流失败时抛出系统异常
	 */
	@PutMapping("/{knowledgeBaseId}/documents/{documentId}/content")
	public RespVO<KnowledgeVO.KnowledgeDocumentImportResponse> replaceDocument(
			@PathVariable String knowledgeBaseId, @PathVariable String documentId,
			@RequestParam("file") MultipartFile file) throws IOException {
		return RespVO.success(this.ingestionService.importFile(knowledgeBaseId, documentId,
			file.getInputStream(), file.getOriginalFilename(), file.getContentType(), file.getSize()));
	}

	/**
	 * 删除稳定文档及全部版本向量。
	 *
	 * @param knowledgeBaseId 知识库 ID
	 * @param documentId      稳定文档 ID
	 * @return 删除响应
	 */
	@DeleteMapping("/{knowledgeBaseId}/documents/{documentId}")
	public RespVO<KnowledgeVO.DeleteKnowledgeDocumentResponse> deleteDocument(
			@PathVariable String knowledgeBaseId, @PathVariable String documentId) {
		return RespVO.success(this.ingestionService.deleteDocument(knowledgeBaseId, documentId));
	}

}
