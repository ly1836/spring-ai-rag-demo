package com.example.rag.chat.rag;

import java.util.List;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.stereotype.Service;

/**
 * 无会话持久化和计费职责的受管 RAG 回答服务。
 */
@Service
public class RagAnswerService {

	private final RagAdvisorFactory advisorFactory;

	private final RagEvidenceExtractor evidenceExtractor;

	private final RagCitationValidator citationValidator;

	/**
	 * 创建受管 RAG 回答服务。
	 *
	 * @param advisorFactory     Advisor 工厂
	 * @param evidenceExtractor 同轮证据提取器
	 * @param citationValidator  引用校验器
	 */
	public RagAnswerService(RagAdvisorFactory advisorFactory, RagEvidenceExtractor evidenceExtractor,
			RagCitationValidator citationValidator) {
		this.advisorFactory = advisorFactory;
		this.evidenceExtractor = evidenceExtractor;
		this.citationValidator = citationValidator;
	}

	/**
	 * 在访问向量库或模型前解析并校验知识库。
	 *
	 * @param knowledgeBaseId 可空知识库 ID
	 * @param retrievalQuery  用户原始检索问题
	 * @param topK            最终召回数量
	 * @param threshold       相似度阈值
	 * @return 请求级 RAG 上下文
	 */
	public RagRequestContext prepare(String knowledgeBaseId, String retrievalQuery,
			int topK, double threshold) {
		return this.advisorFactory.create(knowledgeBaseId, retrievalQuery, topK, threshold);
	}

	/**
	 * 从同一轮非流式响应生成回答结果，不发起第二次检索。
	 *
	 * @param answer   最终回答文本
	 * @param response 同轮模型响应
	 * @param context  请求级 RAG 上下文
	 * @return 回答、证据和引用
	 */
	public RagAnswerResult complete(String answer, ChatResponse response, RagRequestContext context) {
		return completeFromDocuments(answer, extractEvidence(response), context);
	}

	/**
	 * 使用流式过程中已观测到的同轮证据生成回答结果。
	 *
	 * @param answer    最终回答文本
	 * @param documents 同轮证据分片
	 * @param context   请求级 RAG 上下文
	 * @return 回答、证据和引用
	 */
	public RagAnswerResult completeFromDocuments(String answer, List<Document> documents,
			RagRequestContext context) {
		List<Document> safeDocuments = documents == null ? List.of() : List.copyOf(documents);
		return new RagAnswerResult(answer, context.knowledgeBaseId(), safeDocuments,
			this.citationValidator.validate(answer, context.knowledgeBaseId(), safeDocuments));
	}

	/**
	 * 使用生产资格过滤链执行一次纯搜索。
	 *
	 * @param query   搜索文本
	 * @param context 请求级 RAG 上下文
	 * @return 合格检索分片
	 */
	public List<Document> search(String query, RagRequestContext context) {
		return context.documentRetriever().retrieve(Query.builder().text(query).build());
	}

	/**
	 * 提取流式或非流式响应携带的同轮证据。
	 *
	 * @param response 模型响应
	 * @return 同轮证据分片
	 */
	public List<Document> extractEvidence(ChatResponse response) {
		return this.evidenceExtractor.extract(response);
	}

}
