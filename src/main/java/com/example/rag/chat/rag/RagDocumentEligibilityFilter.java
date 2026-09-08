package com.example.rag.chat.rag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.rag.config.TenantContext;
import com.example.rag.knowledge.KnowledgeDocumentService;
import com.example.rag.knowledge.dto.DocumentVersionKey;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

/**
 * 受管 RAG 文档版本资格过滤器。
 */
@Component
public class RagDocumentEligibilityFilter {

	private final KnowledgeDocumentService knowledgeDocumentService;

	/**
	 * 创建文档版本资格过滤器。
	 *
	 * @param knowledgeDocumentService 文档版本服务
	 */
	public RagDocumentEligibilityFilter(KnowledgeDocumentService knowledgeDocumentService) {
		this.knowledgeDocumentService = knowledgeDocumentService;
	}

	/**
	 * 保留当前知识库仍处于 ready 的受管文档版本，并按原召回顺序截断。
	 *
	 * @param knowledgeBaseId 知识库 ID
	 * @param documents       原始过采样结果
	 * @param topK            最终返回数量
	 * @return 合格文档分片
	 */
	public List<Document> filter(String knowledgeBaseId, List<Document> documents, int topK) {
		if (documents == null || documents.isEmpty()) {
			return List.of();
		}
		String entCode = TenantContext.requireEntCode();
		Set<DocumentVersionKey> requested = new LinkedHashSet<>();
		for (Document document : documents) {
			DocumentVersionKey key = toVersionKey(document);
			if (key != null && matchesScope(document, entCode, knowledgeBaseId)
					&& hasCompleteManagedMetadata(document)) {
				requested.add(key);
			}
		}
		Map<DocumentVersionKey, String> readySources =
			this.knowledgeDocumentService.findReadyVersionSources(
			knowledgeBaseId, requested);
		List<Document> eligibleDocuments = documents.stream()
			.filter(document -> matchesScope(document, entCode, knowledgeBaseId))
			// 旧格式或身份不完整的向量必须在查询结果中安全排除。
			.filter(this::hasCompleteManagedMetadata)
			.filter(document -> {
				DocumentVersionKey key = toVersionKey(document);
				Object source = document.getMetadata().get("source");
				return key != null && source != null
					&& String.valueOf(source).equals(readySources.get(key));
			})
			.toList();
		return diversifyByDocument(eligibleDocuments, topK);
	}

	/**
	 * 从向量元数据解析稳定文档版本键。
	 *
	 * @param document 向量文档
	 * @return 合法版本键，旧格式或非法元数据返回 null
	 */
	private DocumentVersionKey toVersionKey(Document document) {
		if (document == null) {
			return null;
		}
		Object documentId = document.getMetadata().get("document_id");
		Object version = document.getMetadata().get("document_version");
		if (!(documentId instanceof String value) || value.isBlank()) {
			return null;
		}
		if (version instanceof Number number) {
			return number.intValue() > 0 ? new DocumentVersionKey(value, number.intValue()) : null;
		}
		if (version instanceof String text) {
			try {
				int parsed = Integer.parseInt(text);
				return parsed > 0 ? new DocumentVersionKey(value, parsed) : null;
			}
			catch (NumberFormatException ex) {
				return null;
			}
		}
		return null;
	}

	/**
	 * 校验向量分片仍属于当前租户和当前知识库。
	 *
	 * @param document        向量文档
	 * @param entCode         当前租户编码
	 * @param knowledgeBaseId 当前知识库 ID
	 * @return 是否属于当前检索范围
	 */
	private boolean matchesScope(Document document, String entCode, String knowledgeBaseId) {
		return document != null
			&& entCode.equals(document.getMetadata().get("ent_code"))
			&& knowledgeBaseId.equals(document.getMetadata().get("knowledge_base_id"));
	}

	/**
	 * 校验可引用分片具备完整且格式正确的稳定身份。
	 *
	 * @param document 向量文档
	 * @return 身份元数据是否完整
	 */
	private boolean hasCompleteManagedMetadata(Document document) {
		if (document == null) {
			return false;
		}
		Object chunkId = document.getMetadata().get("chunk_id");
		Object source = document.getMetadata().get("source");
		Object embeddingModel = document.getMetadata().get(EmbeddingModelMetadata.METADATA_KEY);
		return chunkId instanceof String chunkIdText && !chunkIdText.isBlank()
			&& source instanceof String sourceText && !sourceText.isBlank()
			&& EmbeddingModelMetadata.CURRENT_MODEL_ID.equals(embeddingModel)
			&& readNonNegativeInt(document.getMetadata().get("chunk_index")) != null;
	}

	/**
	 * 最终截断前先保留不同稳定文档的首条候选，再按原相似度顺序补齐。
	 *
	 * @param documents 已通过范围和版本资格校验的候选
	 * @param topK      最终返回数量
	 * @return 保持候选相关性顺序且包含来源分散的结果
	 */
	private List<Document> diversifyByDocument(List<Document> documents, int topK) {
		int limit = Math.max(topK, 0);
		if (limit == 0 || documents.isEmpty()) {
			return List.of();
		}
		List<Document> result = new ArrayList<>(Math.min(limit, documents.size()));
		Set<String> selectedChunkIds = new LinkedHashSet<>();
		Set<String> selectedDocumentIds = new LinkedHashSet<>();
		for (Document document : documents) {
			String documentId = String.valueOf(document.getMetadata().get("document_id"));
			if (selectedDocumentIds.add(documentId)) {
				result.add(document);
				selectedChunkIds.add(String.valueOf(document.getMetadata().get("chunk_id")));
				if (result.size() == limit) {
					return List.copyOf(result);
				}
			}
		}
		for (Document document : documents) {
			String chunkId = String.valueOf(document.getMetadata().get("chunk_id"));
			if (selectedChunkIds.add(chunkId)) {
				result.add(document);
				if (result.size() == limit) {
					break;
				}
			}
		}
		return List.copyOf(result);
	}

	/**
	 * 读取允许从零开始的分片顺序。
	 *
	 * @param value 元数据值
	 * @return 非负整数，非法值返回 null
	 */
	private Integer readNonNegativeInt(Object value) {
		if (value instanceof Number number) {
			return number.intValue() >= 0 ? number.intValue() : null;
		}
		if (value instanceof String text) {
			try {
				int parsed = Integer.parseInt(text);
				return parsed >= 0 ? parsed : null;
			}
			catch (NumberFormatException ex) {
				return null;
			}
		}
		return null;
	}

}
