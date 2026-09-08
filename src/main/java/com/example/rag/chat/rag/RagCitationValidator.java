package com.example.rag.chat.rag;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.rag.vo.ChatVO;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

/**
 * 基于本轮真实证据校验模型回答中的引用编号。
 */
@Component
public class RagCitationValidator {

	private static final int MAX_CITATION_COUNT = 8;

	private static final int MAX_EXCERPT_LENGTH = 500;

	private static final int MAX_CITATION_JSON_BYTES = 32 * 1024;

	private final ObjectMapper objectMapper;

	/**
	 * 创建引用校验器。
	 *
	 * @param objectMapper JSON 序列化器
	 */
	public RagCitationValidator(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	/**
	 * 按回答首次出现顺序生成真实引用快照。
	 *
	 * @param answer          模型回答
	 * @param knowledgeBaseId 实际知识库 ID
	 * @param documents       本轮真实证据
	 * @return 已通过白名单和大小限制的引用
	 */
	public List<ChatVO.CitationResponse> validate(String answer, String knowledgeBaseId,
			List<Document> documents) {
		if (answer == null || answer.isBlank() || documents == null || documents.isEmpty()) {
			return List.of();
		}
		Map<Integer, Document> allowed = buildAllowedDocuments(documents);
		Map<Integer, ChatVO.CitationResponse> selected = new LinkedHashMap<>();
		for (String citationNumber : RagCitationNumberExtractor.extract(answer)) {
			if (selected.size() >= MAX_CITATION_COUNT) {
				break;
			}
			Integer citationId = parsePositiveInt(citationNumber);
			Document document = citationId == null ? null : allowed.get(citationId);
			if (document == null || selected.containsKey(citationId)) {
				continue;
			}
			ChatVO.CitationResponse citation = toCitation(citationId, knowledgeBaseId, document);
			List<ChatVO.CitationResponse> candidate = new ArrayList<>(selected.values());
			candidate.add(citation);
			if (serializedSize(candidate) > MAX_CITATION_JSON_BYTES) {
				break;
			}
			selected.put(citationId, citation);
		}
		return List.copyOf(selected.values());
	}

	/**
	 * 为证据构建允许引用的编号白名单。
	 *
	 * @param documents 本轮证据
	 * @return 引用编号到文档的映射
	 */
	private Map<Integer, Document> buildAllowedDocuments(List<Document> documents) {
		Map<Integer, Document> allowed = new LinkedHashMap<>();
		for (int index = 0; index < documents.size(); index++) {
			Document document = documents.get(index);
			if (document == null) {
				continue;
			}
			Integer citationIndex = readPositiveInt(document.getMetadata().get("citation_index"));
			int resolvedIndex = citationIndex == null ? index + 1 : citationIndex;
			allowed.putIfAbsent(resolvedIndex, document);
		}
		return allowed;
	}

	/**
	 * 将文档分片转换为引用响应。
	 *
	 * @param citationId     引用编号
	 * @param knowledgeBaseId 实际知识库 ID
	 * @param document       证据分片
	 * @return 引用响应
	 */
	private ChatVO.CitationResponse toCitation(int citationId, String knowledgeBaseId,
			Document document) {
		return new ChatVO.CitationResponse(citationId, knowledgeBaseId,
			readText(document, "document_id"), readInt(document, "document_version"),
			readText(document, "chunk_id"), readInt(document, "chunk_index"),
			limitText(readText(document, "source"), 255),
			limitText(normalizeExcerpt(document.getText()), MAX_EXCERPT_LENGTH), document.getScore());
	}

	/**
	 * 读取文档字符串元数据。
	 *
	 * @param document 文档分片
	 * @param key      元数据键
	 * @return 字符串值或空字符串
	 */
	private String readText(Document document, String key) {
		Object value = document.getMetadata().get(key);
		return value == null ? "" : String.valueOf(value);
	}

	/**
	 * 读取文档整数元数据。
	 *
	 * @param document 文档分片
	 * @param key      元数据键
	 * @return 合法整数或零
	 */
	private int readInt(Document document, String key) {
		Integer value = readPositiveInt(document.getMetadata().get(key));
		return value == null ? 0 : value;
	}

	/**
	 * 将未知值转换为正整数。
	 *
	 * @param value 未知值
	 * @return 正整数或 null
	 */
	private Integer readPositiveInt(Object value) {
		if (value instanceof Number number) {
			return number.intValue() > 0 ? number.intValue() : null;
		}
		return value instanceof String text ? parsePositiveInt(text) : null;
	}

	/**
	 * 解析正整数文本。
	 *
	 * @param value 数字文本
	 * @return 正整数或 null
	 */
	private Integer parsePositiveInt(String value) {
		try {
			int parsed = Integer.parseInt(value);
			return parsed > 0 ? parsed : null;
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	/**
	 * 规范引用摘要中的连续空白。
	 *
	 * @param value 原始摘要
	 * @return 单行摘要
	 */
	private String normalizeExcerpt(String value) {
		return value == null ? "" : value.replaceAll("\\s+", " ").trim();
	}

	/**
	 * 按 Unicode 字符安全截断文本。
	 *
	 * @param value     原始文本
	 * @param maxLength 最大字符数
	 * @return 截断后的文本
	 */
	private String limitText(String value, int maxLength) {
		if (value == null) {
			return "";
		}
		int codePointCount = value.codePointCount(0, value.length());
		if (codePointCount <= maxLength) {
			return value;
		}
		return value.substring(0, value.offsetByCodePoints(0, maxLength));
	}

	/**
	 * 计算引用数组 UTF-8 JSON 大小。
	 *
	 * @param citations 引用数组
	 * @return 序列化字节数，失败时返回最大整数
	 */
	private int serializedSize(List<ChatVO.CitationResponse> citations) {
		try {
			return this.objectMapper.writeValueAsString(citations).getBytes(StandardCharsets.UTF_8).length;
		}
		catch (JacksonException ex) {
			return Integer.MAX_VALUE;
		}
	}

}
