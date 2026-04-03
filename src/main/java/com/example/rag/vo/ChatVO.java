package com.example.rag.vo;

import java.util.List;
import java.util.Objects;

/**
 * AI 问答模块 VO —— {@link com.example.rag.chat.ChatController} 的入参和出参定义。
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
	 */
	public record AskRequest(String question, String conversationId, String mode, String modelId) {
		public AskRequest {
			question = Objects.requireNonNullElse(question, "");
			conversationId = Objects.requireNonNullElse(conversationId, "");
			mode = (mode == null || mode.isBlank()) ? "auto" : mode;
			modelId = Objects.requireNonNullElse(modelId, "");
		}
	}

	/**
	 * 文档相似度搜索入参。
	 *
	 * @param query 搜索关键词
	 * @param topK  返回结果数量，默认 5
	 */
	public record DocSearchRequest(String query, Integer topK) {
		public DocSearchRequest {
			query = Objects.requireNonNullElse(query, "");
			topK = (topK == null || topK <= 0) ? 5 : topK;
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
	 */
	public record AskResponse(String conversationId, String question, String answer, String mode) {
	}

	/**
	 * 文档搜索结果中的单个文档片段。
	 *
	 * @param text   文档片段文本内容
	 * @param source 来源文件名
	 * @param score  与查询的相似度分数（0~1，越高越相似）
	 */
	public record DocSnippetResponse(String text, String source, Double score) {
	}

	/**
	 * 文档搜索出参。
	 *
	 * @param query   原始搜索关键词
	 * @param results 相似文档片段列表
	 */
	public record DocSearchResponse(String query, List<DocSnippetResponse> results) {
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
