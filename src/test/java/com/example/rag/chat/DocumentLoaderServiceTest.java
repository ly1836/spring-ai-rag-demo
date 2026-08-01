package com.example.rag.chat;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import com.example.rag.config.TenantContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 文档加载服务测试。
 */
class DocumentLoaderServiceTest {

	/**
	 * 验证超过历史 200 个 chunk 上限的文档不会产生超大尾块。
	 */
	@Test
	@SuppressWarnings("unchecked")
	public void shouldKeepLongDocumentChunksWithinEmbeddingModelLimit() throws Exception {
		VectorStore vectorStore = mock(VectorStore.class);
		DocumentLoaderService service = new DocumentLoaderService(vectorStore);
		ArgumentCaptor<List<Document>> chunksCaptor = ArgumentCaptor.forClass(List.class);
		String longText = "这是用于验证超长文档切分的测试内容。".repeat(3000);

		TenantContext.setEntCode("ENT001");
		try (service; HuggingFaceTokenizer tokenizer = createEmbeddingTokenizer()) {
			int chunkCount = service.loadText(longText);

			verify(vectorStore, atLeastOnce()).add(chunksCaptor.capture());
			List<Document> chunks = chunksCaptor.getAllValues().stream()
				.flatMap(List::stream)
				.toList();
			assertThat(chunkCount).isGreaterThan(200);
			assertThat(chunks).allSatisfy(chunk ->
				assertThat(tokenizer.encode(chunk.getText()).getIds().length).isLessThanOrEqualTo(128));
		}
		finally {
			TenantContext.clear();
		}
	}

	/**
	 * 验证同租户同来源先删除旧数据，再分批写入新分片。
	 */
	@Test
	@SuppressWarnings("unchecked")
	public void shouldReplaceExistingSourceBeforeBatchWrite() {
		VectorStore vectorStore = mock(VectorStore.class);
		DocumentLoaderService service = new DocumentLoaderService(vectorStore);
		ArgumentCaptor<List<Document>> chunksCaptor = ArgumentCaptor.forClass(List.class);
		TenantContext.setEntCode("ENT001");
		try (service) {
			int chunkCount = service.loadText("分批写入向量数据。".repeat(5000));

			InOrder ordered = inOrder(vectorStore);
			ordered.verify(vectorStore).delete(any(Filter.Expression.class));
			ordered.verify(vectorStore, atLeastOnce()).add(chunksCaptor.capture());
			List<List<Document>> batches = chunksCaptor.getAllValues();
			assertThat(batches).allSatisfy(batch -> assertThat(batch).hasSizeLessThanOrEqualTo(100));
			assertThat(batches.stream().mapToInt(List::size).sum()).isEqualTo(chunkCount);
		}
		finally {
			TenantContext.clear();
		}
	}

	/**
	 * 验证超过提取文本预算的内容不会进入切分和向量写入。
	 */
	@Test
	public void shouldRejectTextBeyondExtractionBudget() {
		VectorStore vectorStore = mock(VectorStore.class);
		DocumentLoaderService service = new DocumentLoaderService(vectorStore);
		TenantContext.setEntCode("ENT001");
		try (service) {
			String oversizedText = "文".repeat(5_000_001);

			assertThatThrownBy(() -> service.loadText(oversizedText))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("文档解析文本超过");
			verify(vectorStore, org.mockito.Mockito.never()).add(anyList());
		}
		finally {
			TenantContext.clear();
		}
	}

	/**
	 * 验证向量批次写入失败后会删除同来源的不完整数据。
	 */
	@Test
	public void shouldCleanPartialSourceWhenBatchWriteFails() {
		VectorStore vectorStore = mock(VectorStore.class);
		doNothing().doThrow(new IllegalStateException("模拟第二批写入失败"))
			.when(vectorStore).add(anyList());
		DocumentLoaderService service = new DocumentLoaderService(vectorStore);
		TenantContext.setEntCode("ENT001");
		try (service) {
			assertThatThrownBy(() -> service.loadText("分批失败清理测试。".repeat(5000)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("模拟第二批写入失败");
			verify(vectorStore, times(2)).delete(any(Filter.Expression.class));
		}
		finally {
			TenantContext.clear();
		}
	}

	/**
	 * 创建与生产嵌入模型完全相同的 WordPiece 分词器。
	 *
	 * @return 实际嵌入分词器
	 */
	private HuggingFaceTokenizer createEmbeddingTokenizer() throws Exception {
		try (InputStream inputStream = new ClassPathResource(
				"models/embedding/tokenizer.json").getInputStream()) {
			return HuggingFaceTokenizer.newInstance(inputStream, Map.of(
				"addSpecialTokens", "true",
				"truncation", "false",
				"padding", "false"));
		}
	}

}
