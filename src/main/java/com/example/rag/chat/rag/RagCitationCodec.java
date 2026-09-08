package com.example.rag.chat.rag;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.example.rag.vo.ChatVO;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

/**
 * RAG 引用快照 JSON 编解码器。
 */
@Component
public class RagCitationCodec {

	private static final int MAX_CITATION_COUNT = 8;

	private static final int MAX_CITATION_JSON_BYTES = 32 * 1024;

	private static final TypeReference<List<ChatVO.CitationResponse>> CITATION_LIST_TYPE =
		new TypeReference<>() { };

	private final ObjectMapper objectMapper;

	/**
	 * 创建引用快照编解码器。
	 *
	 * @param objectMapper JSON 序列化器
	 */
	public RagCitationCodec(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	/**
	 * 序列化已验证引用，并再次执行持久化大小防线。
	 *
	 * @param citations 引用数组
	 * @return 可解析 JSON
	 * @throws IllegalArgumentException 引用不符合数量或大小边界时抛出
	 */
	public String encode(List<ChatVO.CitationResponse> citations) {
		List<ChatVO.CitationResponse> safeCitations = citations == null ? List.of() : List.copyOf(citations);
		if (safeCitations.size() > MAX_CITATION_COUNT) {
			throw new IllegalArgumentException("RAG 引用数量超过 8 条");
		}
		try {
			String json = this.objectMapper.writeValueAsString(safeCitations);
			if (json.getBytes(StandardCharsets.UTF_8).length > MAX_CITATION_JSON_BYTES) {
				throw new IllegalArgumentException("RAG 引用快照超过 32 KiB");
			}
			return json;
		}
		catch (JacksonException ex) {
			throw new IllegalArgumentException("RAG 引用快照序列化失败", ex);
		}
	}

	/**
	 * 反序列化历史引用，非法数据交由调用方安全降级。
	 *
	 * @param json 引用 JSON
	 * @return 引用数组
	 * @throws IllegalArgumentException JSON 损坏、超限或数量非法时抛出
	 */
	public List<ChatVO.CitationResponse> decode(String json) {
		if (json == null || json.isBlank()) {
			return List.of();
		}
		if (json.getBytes(StandardCharsets.UTF_8).length > MAX_CITATION_JSON_BYTES) {
			throw new IllegalArgumentException("RAG 引用快照超过 32 KiB");
		}
		try {
			List<ChatVO.CitationResponse> citations = this.objectMapper.readValue(json, CITATION_LIST_TYPE);
			if (citations == null || citations.size() > MAX_CITATION_COUNT) {
				throw new IllegalArgumentException("RAG 引用快照数量非法");
			}
			return List.copyOf(citations);
		}
		catch (JacksonException | NullPointerException ex) {
			throw new IllegalArgumentException("RAG 引用快照解析失败", ex);
		}
	}

}
