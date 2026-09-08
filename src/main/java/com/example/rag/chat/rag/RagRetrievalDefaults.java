package com.example.rag.chat.rag;

/**
 * 生产问答与真实评测共用的 RAG 检索默认参数。
 */
public final class RagRetrievalDefaults {

	/** auto 模式保持兼容的召回数量。 */
	public static final int AUTO_TOP_K = 5;

	/** auto 模式保持兼容的相似度阈值。 */
	public static final double AUTO_SIMILARITY_THRESHOLD = 0.5;

	/** knowledge 模式覆盖相邻片段的召回数量。 */
	public static final int KNOWLEDGE_TOP_K = 8;

	/** knowledge 模式兼容中文技术文档的相似度阈值。 */
	public static final double KNOWLEDGE_SIMILARITY_THRESHOLD = 0.25;

	/**
	 * 禁止实例化 RAG 检索默认参数类。
	 */
	private RagRetrievalDefaults() {
	}

}
