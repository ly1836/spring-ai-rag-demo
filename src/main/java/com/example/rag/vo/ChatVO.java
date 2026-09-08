package com.example.rag.vo;

import java.util.List;
import java.util.Objects;

/**
 * AI 问答模块 VO —— {@link com.example.rag.controller.ChatController} 的入参和出参定义。
 * <p>
 * 包含文档管理、AI 问答、文档搜索三类接口的请求/响应对象。
 */
public final class ChatVO {

	private ChatVO() {
	}

	// ==================== Request ====================

	/**
	 * AI 问答入参（/api/ask 和 /api/ask/stream 共用）。
	 *
	 * @param question       用户问题
	 * @param conversationId 会话 ID（为空则自动创建新会话）
	 * @param mode           回答模式：auto（智能）/ data（数据查询）/ knowledge（知识问答），默认 auto
	 * @param modelId        模型 ID（对应 app.models[].id），为空时使用默认模型
	 * @param knowledgeBaseId auto/knowledge 模式使用的可空知识库 ID
	 */
	public record AskRequest(String question, String conversationId, String mode, String modelId,
			String knowledgeBaseId) {
		public AskRequest {
			question = Objects.requireNonNullElse(question, "");
			conversationId = Objects.requireNonNullElse(conversationId, "");
			mode = (mode == null || mode.isBlank()) ? "auto" : mode;
			modelId = Objects.requireNonNullElse(modelId, "");
			knowledgeBaseId = Objects.requireNonNullElse(knowledgeBaseId, "");
		}

		/**
		 * 兼容旧调用方构造不含知识库 ID 的请求。
		 *
		 * @param question       用户问题
		 * @param conversationId 会话 ID
		 * @param mode           回答模式
		 * @param modelId        模型 ID
		 */
		public AskRequest(String question, String conversationId, String mode, String modelId) {
			this(question, conversationId, mode, modelId, "");
		}
	}

	/**
	 * 文档相似度搜索入参。
	 *
	 * @param query 搜索关键词
	 * @param topK  返回结果数量，默认 5，最大 24
	 * @param knowledgeBaseId 可空知识库 ID
	 */
	public record DocSearchRequest(String query, Integer topK, String knowledgeBaseId) {
		public DocSearchRequest {
			query = Objects.requireNonNullElse(query, "");
			topK = (topK == null || topK <= 0) ? 5 : topK;
			knowledgeBaseId = Objects.requireNonNullElse(knowledgeBaseId, "");
		}

		/**
		 * 兼容旧调用方构造默认知识库搜索请求。
		 *
		 * @param query 搜索关键词
		 * @param topK 返回结果数量
		 */
		public DocSearchRequest(String query, Integer topK) {
			this(query, topK, "");
		}
	}

	// ==================== Response ====================

	/**
	 * 加载预置文档出参。
	 *
	 * @param chunksLoaded 成功导入的文档片段数量
	 */
	public record LoadDocumentsResponse(int chunksLoaded) {
	}

	/**
	 * 上传文件出参。
	 *
	 * @param filename     原始文件名
	 * @param chunksLoaded 文件拆分后导入的文档片段数量
	 */
	public record UploadFileResponse(String filename, int chunksLoaded) {
	}

	/**
	 * AI 问答出参。
	 *
	 * @param conversationId 会话 ID（新创建或复用已有的）
	 * @param question       用户原始问题
	 * @param answer         LLM 生成的回答
	 * @param mode           实际使用的回答模式
	 * @param chart          LLM 选择并由后端编译的图表，无图表时为空
	 * @param knowledgeBaseId 实际使用的知识库 ID
	 * @param citations      经验证的引用数组
	 * @param ragDocCount    本轮合格召回分片数
	 */
	public record AskResponse(String conversationId, String question, String answer, String mode,
			ChartVO.ChartSpec chart, String knowledgeBaseId, List<CitationResponse> citations,
			int ragDocCount) {
	}

	/**
	 * 类型化 SSE 文本增量事件。
	 *
	 * @param text 本次文本增量
	 */
	public record StreamDelta(String text) {
	}

	/**
	 * 回答引用对象。
	 *
	 * @param citationId       回答中的引用序号
	 * @param knowledgeBaseId  知识库 ID
	 * @param documentId       稳定文档 ID
	 * @param documentVersion  文档版本号
	 * @param chunkId          分片 ID
	 * @param chunkIndex       分片顺序
	 * @param source           来源名称
	 * @param excerpt          安全摘要
	 * @param score            相似度分数
	 */
	public record CitationResponse(int citationId, String knowledgeBaseId, String documentId,
			int documentVersion, String chunkId, int chunkIndex, String source,
			String excerpt, Double score) {
	}

	/**
	 * 类型化 SSE 引用事件。
	 *
	 * @param knowledgeBaseId 实际知识库 ID
	 * @param citations       本轮完整引用数组
	 */
	public record StreamCitations(String knowledgeBaseId, List<CitationResponse> citations) {
	}

	/**
	 * 类型化 SSE 图表事件。
	 *
	 * @param chart 本轮唯一图表
	 */
	public record StreamChart(ChartVO.ChartSpec chart) {
	}

	/**
	 * 类型化 SSE 完成事件。
	 *
	 * @param conversationId 会话 ID
	 * @param status         完成状态
	 * @param knowledgeBaseId 本轮实际使用的知识库 ID，非 RAG 模式为空
	 */
	public record StreamDone(String conversationId, String status, String knowledgeBaseId) {
	}

	/**
	 * 类型化 SSE 错误事件。
	 *
	 * @param code    稳定错误码
	 * @param message 安全错误信息
	 */
	public record StreamError(String code, String message) {
	}

	/**
	 * 文档搜索结果中的单个文档片段。
	 *
	 * @param text             文档片段文本内容
	 * @param source           来源文件名
	 * @param score            与查询的相似度分数（0~1，越高越相似）
	 * @param documentId       稳定文档 ID
	 * @param documentVersion  文档版本
	 * @param chunkId          稳定分片 ID
	 * @param chunkIndex       分片顺序
	 */
	public record DocSnippetResponse(String text, String source, Double score, String documentId,
			int documentVersion, String chunkId, int chunkIndex) {
	}

	/**
	 * 文档搜索出参。
	 *
	 * @param query           原始搜索关键词
	 * @param knowledgeBaseId 实际使用的知识库 ID
	 * @param results         相似文档片段列表
	 */
	public record DocSearchResponse(String query, String knowledgeBaseId,
			List<DocSnippetResponse> results) {
	}

	/**
	 * AI 生成的预置示例问题出参。
	 *
	 * @param hints 示例问题列表
	 */
	public record HintsResponse(List<String> hints) {
	}

	/**
	 * 单个可用模型信息。
	 *
	 * @param id        模型 ID（前端传参使用）
	 * @param label     展示名称
	 * @param modelName 实际模型名称
	 * @param isDefault 是否为默认模型
	 */
	public record ModelItem(String id, String label, String modelName, boolean isDefault) {
	}

	/**
	 * 可用模型列表出参。
	 *
	 * @param models 模型列表
	 */
	public record ModelsResponse(List<ModelItem> models) {
	}

}
