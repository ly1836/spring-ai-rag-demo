package com.example.rag.chat.rag;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import org.junit.jupiter.api.Test;

import org.springframework.core.io.ClassPathResource;
import org.springframework.ai.transformers.TransformersEmbeddingModel;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 固定多语言嵌入模型资源契约测试。
 */
class EmbeddingModelResourceTest {

	/** 固定 ONNX 资源摘要。 */
	private static final String MODEL_SHA256 =
		"98a01d88b7de996cdea58c32ca71208c09968d143798814b2ea09d3439dc334f";

	/** 固定分词器资源摘要。 */
	private static final String TOKENIZER_SHA256 =
		"2c3387be76557bd40970cec13153b3bbf80407865484b209e655e5e4729076b8";

	/**
	 * 验证模型摘要、ONNX 输入输出和中文分词能力均符合生产配置。
	 *
	 * @throws IOException 资源读取失败
	 * @throws OrtException ONNX 模型无法加载
	 */
	@Test
	public void shouldLoadPinnedMultilingualEmbeddingResources() throws IOException, OrtException {
		ClassPathResource model = new ClassPathResource("models/embedding/model.onnx");
		ClassPathResource tokenizerResource =
			new ClassPathResource("models/embedding/tokenizer.json");
		assertThat(sha256(model)).isEqualTo(MODEL_SHA256);
		assertThat(sha256(tokenizerResource)).isEqualTo(TOKENIZER_SHA256);

		try (OrtSession session = OrtEnvironment.getEnvironment()
			.createSession(model.getFile().getAbsolutePath())) {
			assertThat(session.getInputNames()).contains("input_ids", "attention_mask");
			assertThat(session.getOutputNames()).contains("last_hidden_state");
		}

		try (InputStream inputStream = tokenizerResource.getInputStream();
				HuggingFaceTokenizer tokenizer = HuggingFaceTokenizer.newInstance(inputStream, Map.of(
					"addSpecialTokens", "true", "truncation", "true",
					"padding", "true", "maxLength", "128"))) {
			long[] channelTokens = tokenizer.encode("渠道技术方案").getIds();
			long[] jvmTokens = tokenizer.encode("JVM 垃圾回收算法").getIds();
			assertThat(channelTokens).hasSizeLessThanOrEqualTo(EmbeddingModelMetadata.MAX_TOKENS);
			assertThat(Arrays.equals(channelTokens, jvmTokens)).isFalse();
			assertThat(Arrays.stream(channelTokens).distinct().count()).isGreaterThan(3);
		}
	}

	/**
	 * 验证 Spring AI 使用新模型生成 384 维中文向量，且相关中文内容排序更靠前。
	 *
	 * @throws Exception 模型初始化失败
	 */
	@Test
	public void shouldEmbedChineseContentWithExpectedSemanticOrder() throws Exception {
		TransformersEmbeddingModel model = new TransformersEmbeddingModel();
		model.setModelResource(new ClassPathResource("models/embedding/model.onnx"));
		model.setTokenizerResource(new ClassPathResource("models/embedding/tokenizer.json"));
		model.setTokenizerOptions(Map.of(
			"addSpecialTokens", "true", "truncation", "true",
			"padding", "true", "maxLength", "128"));
		model.afterPropertiesSet();

		float[] query = model.embed("介绍一下渠道技术方案");
		float[] relevant = model.embed("渠道技术方案包括整体架构、接口设计和数据流转");
		float[] irrelevant = model.embed("JVM 垃圾回收算法与内存管理");

		assertThat(query).hasSize(EmbeddingModelMetadata.DIMENSIONS);
		for (float value : query) {
			assertThat(Float.isFinite(value)).isTrue();
		}
		assertThat(cosineSimilarity(query, relevant))
			.isGreaterThan(cosineSimilarity(query, irrelevant));
	}

	/**
	 * 计算两个向量的余弦相似度。
	 *
	 * @param left  左向量
	 * @param right 右向量
	 * @return 余弦相似度
	 */
	private double cosineSimilarity(float[] left, float[] right) {
		double dotProduct = 0.0;
		double leftNorm = 0.0;
		double rightNorm = 0.0;
		for (int index = 0; index < left.length; index++) {
			dotProduct += left[index] * right[index];
			leftNorm += left[index] * left[index];
			rightNorm += right[index] * right[index];
		}
		return dotProduct / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
	}

	/**
	 * 计算 classpath 资源 SHA-256。
	 *
	 * @param resource 待校验资源
	 * @return 小写十六进制摘要
	 * @throws IOException 资源读取失败
	 */
	private String sha256(ClassPathResource resource) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (InputStream inputStream = resource.getInputStream()) {
				byte[] buffer = new byte[64 * 1024];
				int read;
				while ((read = inputStream.read(buffer)) >= 0) {
					if (read > 0) {
						digest.update(buffer, 0, read);
					}
				}
			}
			return HexFormat.of().formatHex(digest.digest());
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("当前运行环境不支持 SHA-256", ex);
		}
	}

}
