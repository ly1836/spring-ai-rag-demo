package com.example.rag.chat.rag;

/**
 * 当前生产嵌入模型及向量元数据约束。
 */
public final class EmbeddingModelMetadata {

	/** 写入 PgVector 文档元数据的模型身份字段。 */
	public static final String METADATA_KEY = "embedding_model";

	/**
	 * 当前模型唯一身份；资源或量化方式变化时必须同步修改，避免新旧向量混用。
	 */
	public static final String CURRENT_MODEL_ID =
		"paraphrase-multilingual-MiniLM-L12-v2@e8f8c211-avx2-int8";

	/** 当前模型输出向量维度。 */
	public static final int DIMENSIONS = 384;

	/** 当前模型允许的最大输入 token 数。 */
	public static final int MAX_TOKENS = 128;

	/** 禁止实例化模型元数据约束类。 */
	private EmbeddingModelMetadata() {
	}

}
