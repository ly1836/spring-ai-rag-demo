package com.example.rag.chat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import jakarta.annotation.PreDestroy;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.rag.chat.rag.EmbeddingModelMetadata;
import com.example.rag.config.TenantContext;
import com.example.rag.knowledge.dto.ManagedDocumentLoadResult;
import com.example.rag.knowledge.dto.ManagedDocumentMetadata;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

/**
 * 产品手册文档加载服务。
 * <p>
 * 负责将产品手册（PDF、Word、TXT 等）导入到 PgVector 向量数据库中。
 * 处理流程：读取文件 → 按 Token 分割成 chunk → 标记租户元数据 → 写入向量库。
 * <p>
 * 写入向量库时，Spring AI 会自动调用 EmbeddingModel 将文本转为向量再存储。
 */
@Service
public class DocumentLoaderService implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(DocumentLoaderService.class);

	/** 单个文档允许提取的最大文本字符数。 */
	private static final int MAX_EXTRACTED_TEXT_CHARS = 5_000_000;

	/** 单个文档允许写入的最大分片数。 */
	private static final int MAX_DOCUMENT_CHUNKS = 20_000;

	/** 每次向量化并写入数据库的分片数。 */
	private static final int VECTOR_WRITE_BATCH_SIZE = 100;

	/** 当前 ONNX 嵌入模型允许的最大 token 数。 */
	private static final int EMBEDDING_MAX_TOKENS = EmbeddingModelMetadata.MAX_TOKENS;

	/** 实际嵌入模型使用的分词器资源。 */
	private static final String EMBEDDING_TOKENIZER_RESOURCE = "models/embedding/tokenizer.json";

	private final VectorStore vectorStore;

	private final TokenTextSplitter splitter;

	/** 使用与 ONNX 嵌入模型一致的 WordPiece 分词器校验真实 token 边界。 */
	private final HuggingFaceTokenizer embeddingTokenizer;

	public DocumentLoaderService(VectorStore vectorStore) {
		this.vectorStore = vectorStore;
		this.splitter = TokenTextSplitter.builder()
			// 为本地模型的 128 token 上限预留特殊标记和分词差异空间。
			.withChunkSize(120)
			.withMinChunkSizeChars(200)
			.withMinChunkLengthToEmbed(50)
			// 超出最终分片预算时保留剩余文本，由后续真实 token 校验统一拒绝。
			.withMaxNumChunks(MAX_DOCUMENT_CHUNKS)
			.build();
		this.embeddingTokenizer = createEmbeddingTokenizer();
	}

	/**
	 * 批量加载 classpath:docs/ 目录下的所有文件到向量库。
	 *
	 * @return 总共加载的 chunk 数量
	 */
	public int loadFromClasspath() {
		try {
			var resolver = new PathMatchingResourcePatternResolver();
			Resource[] resources = resolver.getResources("classpath:docs/*.*");
			int total = 0;
			for (Resource resource : resources) {
				String filename = resource.getFilename();
				if (filename == null) continue;
				total += loadResource(resource, filename);
			}
			return total;
		}
		catch (Exception e) {
			throw new RuntimeException("从 classpath 加载文档失败", e);
		}
	}

	/**
	 * 加载用户上传的文件到向量库。
	 * 支持 PDF、Word (.docx)、Excel (.xlsx)、TXT、HTML 等格式（通过 Apache Tika 解析）。
	 *
	 * @param inputStream 文件输入流
	 * @param filename    文件名（用于日志和元数据）
	 * @return 加载的 chunk 数量
	 */
	public int loadFile(InputStream inputStream, String filename) {
		var resource = new InputStreamResource(inputStream);
		return loadResource(resource, filename);
	}

	/**
	 * 加载纯文本内容到向量库。
	 *
	 * @param text 文本内容
	 * @return 加载的 chunk 数量
	 */
	public int loadText(String text) {
		List<Document> docs = List.of(new Document(text));
		List<Document> chunks = splitAndValidate(docs);
		addMetadataAndStore(chunks, "user-input");
		return chunks.size();
	}

	/**
	 * 内部方法：读取文件 → 分割 → 存入向量库。
	 * 所有支持的文件统一使用受限 TikaDocumentReader，确保解析阶段执行文本字符预算。
	 */
	private int loadResource(Resource resource, String filename) {
		log.info("开始加载文档: {}", filename);
		List<Document> chunks = readAndSplitResource(resource);
		addMetadataAndStore(chunks, filename);
		log.info("已从文档 {} 导入 {} 个分片", filename, chunks.size());
		return chunks.size();
	}

	/**
	 * 为每个 chunk 添加元数据，然后批量写入向量库。
	 * 元数据包括：
	 * - ent_code: 租户标识（从 TenantContext 获取，确保数据隔离）
	 * - source: 来源文件名
	 */
	private void addMetadataAndStore(List<Document> chunks, String source) {
		if (chunks.isEmpty()) {
			throw new IllegalArgumentException("文档未提取到可入库文本");
		}
		String entCode = TenantContext.requireEntCode();
		for (Document chunk : chunks) {
			chunk.getMetadata().put("ent_code", entCode);
			chunk.getMetadata().put("source", source);
			chunk.getMetadata().put(EmbeddingModelMetadata.METADATA_KEY,
				EmbeddingModelMetadata.CURRENT_MODEL_ID);
		}
		Filter.Expression sourceFilter = buildSourceFilter(entCode, source);
		// 同租户同来源重新导入前先清理旧分片，避免重复向量干扰召回。
		vectorStore.delete(sourceFilter);
		try {
			for (int start = 0; start < chunks.size(); start += VECTOR_WRITE_BATCH_SIZE) {
				int end = Math.min(start + VECTOR_WRITE_BATCH_SIZE, chunks.size());
				vectorStore.add(List.copyOf(chunks.subList(start, end)));
			}
		}
		catch (RuntimeException ex) {
			try {
				// 批次失败时清理已写入的本次分片，避免留下不完整文档。
				vectorStore.delete(sourceFilter);
			}
			catch (RuntimeException cleanupEx) {
				log.warn("文档导入失败后清理残留分片失败: source={}, error={}",
					source, cleanupEx.getMessage());
			}
			throw ex;
		}
	}

	/**
	 * 使用初步语义切分后，再按实际 WordPiece token 边界拆分。
	 *
	 * @param documents 已提取的文档内容
	 * @return 可直接交给嵌入模型的分片
	 */
	private List<Document> splitAndValidate(List<Document> documents) {
		validateExtractedTextSize(documents);
		List<Document> initialChunks = this.splitter.transform(documents);
		if (initialChunks.isEmpty()) {
			// 完整短文档不足分片器的最小长度时仍应入库，后续继续校验真实 token 上限。
			initialChunks = documents.stream()
				.filter(document -> document.getText() != null && !document.getText().isBlank())
				.toList();
		}
		if (initialChunks.size() > MAX_DOCUMENT_CHUNKS) {
			throw new IllegalArgumentException("文档分片数超过 " + MAX_DOCUMENT_CHUNKS + " 个");
		}

		List<Document> safeChunks = new ArrayList<>(initialChunks.size());
		Deque<Document> pendingChunks = new ArrayDeque<>(initialChunks);
		while (!pendingChunks.isEmpty()) {
			Document chunk = pendingChunks.removeFirst();
			String text = chunk.getText();
			if (text == null || text.isBlank()) {
				continue;
			}
			if (countEmbeddingTokens(text) <= EMBEDDING_MAX_TOKENS) {
				safeChunks.add(chunk);
				if (safeChunks.size() > MAX_DOCUMENT_CHUNKS) {
					throw new IllegalArgumentException("文档分片数超过 " + MAX_DOCUMENT_CHUNKS + " 个");
				}
				continue;
			}

			int splitIndex = findSafeSplitIndex(text);
			if (splitIndex <= 0 || splitIndex >= text.length()) {
				throw new IllegalArgumentException("文档分片无法收敛到嵌入模型 token 上限");
			}
			Map<String, Object> metadata = chunk.getMetadata();
			// 按栈的逆序压入右、左半段，保持最终分片的原文顺序。
			pendingChunks.addFirst(new Document(text.substring(splitIndex).trim(), metadata));
			pendingChunks.addFirst(new Document(text.substring(0, splitIndex).trim(), metadata));
		}
		return List.copyOf(safeChunks);
	}

	/**
	 * 校验单个文档的提取文本总量。
	 *
	 * @param documents 已提取的文档内容
	 */
	private void validateExtractedTextSize(List<Document> documents) {
		long totalChars = 0;
		for (Document document : documents) {
			String text = document.getText();
			if (text != null) {
				totalChars += text.length();
				if (totalChars > MAX_EXTRACTED_TEXT_CHARS) {
					throw new IllegalArgumentException(
						"文档解析文本超过 " + MAX_EXTRACTED_TEXT_CHARS + " 个字符");
				}
			}
		}
	}

	/**
	 * 计算文本在实际 ONNX WordPiece 分词器下的 token 数。
	 *
	 * @param text 待校验文本
	 * @return 包含模型特殊标记的 token 数
	 */
	private int countEmbeddingTokens(String text) {
		return this.embeddingTokenizer.encode(text).getIds().length;
	}

	/**
	 * 在文本中点附近寻找不破坏代理字符对的切分位置。
	 *
	 * @param text 超过 token 上限的文本
	 * @return 切分位置
	 */
	private int findSafeSplitIndex(String text) {
		int middle = text.length() / 2;
		int lowerBound = text.length() / 4;
		int splitIndex = middle;
		for (int index = middle; index >= lowerBound; index--) {
			char current = text.charAt(index - 1);
			if (Character.isWhitespace(current) || ".?!;。？！；\n".indexOf(current) >= 0) {
				splitIndex = index;
				break;
			}
		}
		if (splitIndex > 0 && splitIndex < text.length()
				&& Character.isHighSurrogate(text.charAt(splitIndex - 1))
				&& Character.isLowSurrogate(text.charAt(splitIndex))) {
			splitIndex--;
		}
		return splitIndex;
	}

	/**
	 * 构建同租户同来源的向量删除条件。
	 *
	 * @param entCode 租户编码
	 * @param source  文档来源
	 * @return 向量过滤表达式
	 */
	private Filter.Expression buildSourceFilter(String entCode, String source) {
		FilterExpressionBuilder builder = new FilterExpressionBuilder();
		return builder.and(builder.eq("ent_code", entCode), builder.eq("source", source)).build();
	}

	/**
	 * 判断 Tika 异常链是否表示提取文本超限。
	 *
	 * @param error Tika 解析异常
	 * @return 是否超过文本字符上限
	 */
	private boolean isTextLimitExceeded(Throwable error) {
		Throwable current = error;
		while (current != null) {
			if ("WriteLimitReachedException".equals(current.getClass().getSimpleName())) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	/**
	 * 创建禁用截断与填充的嵌入模型分词器，便于读取真实 token 数。
	 *
	 * @return WordPiece 分词器
	 */
	private static HuggingFaceTokenizer createEmbeddingTokenizer() {
		try (InputStream inputStream = new ClassPathResource(EMBEDDING_TOKENIZER_RESOURCE).getInputStream()) {
			return HuggingFaceTokenizer.newInstance(inputStream, Map.of(
				"addSpecialTokens", "true",
				"truncation", "false",
				"padding", "false"));
		}
		catch (IOException ex) {
			throw new IllegalStateException("初始化嵌入模型分词器失败", ex);
		}
	}

	/** 释放本地 WordPiece 分词器的原生资源。 */
	@Override
	@PreDestroy
	public void close() {
		this.embeddingTokenizer.close();
	}

	/**
	 * 加载受管文件并写入带稳定证据身份的向量分片。
	 *
	 * @param inputStream 文件输入流
	 * @param metadata    受管文档身份
	 * @return 分片数量和文件摘要
	 * @throws IllegalArgumentException 文档内容或身份非法时抛出 PARAM_ERROR
	 */
	public ManagedDocumentLoadResult loadManagedFile(InputStream inputStream,
			ManagedDocumentMetadata metadata) {
		validateManagedMetadata(metadata);
		MessageDigest digest = createSha256Digest();
		DigestInputStream digestInputStream = new DigestInputStream(inputStream, digest);
		List<Document> chunks = readAndSplitResource(new InputStreamResource(digestInputStream));
		addManagedMetadataAndStore(chunks, metadata);
		return new ManagedDocumentLoadResult(chunks.size(), HexFormat.of().formatHex(digest.digest()));
	}

	/**
	 * 加载受管纯文本，供固定评测夹具复用生产切分和向量写入链路。
	 *
	 * @param text     文本内容
	 * @param metadata 受管文档身份
	 * @return 分片数量和文本摘要
	 * @throws IllegalArgumentException 文本或身份非法时抛出 PARAM_ERROR
	 */
	public ManagedDocumentLoadResult loadManagedText(String text, ManagedDocumentMetadata metadata) {
		validateManagedMetadata(metadata);
		if (text == null || text.isBlank()) {
			throw new IllegalArgumentException("文档未提取到可入库文本");
		}
		List<Document> chunks = splitAndValidate(List.of(new Document(text)));
		addManagedMetadataAndStore(chunks, metadata);
		MessageDigest digest = createSha256Digest();
		return new ManagedDocumentLoadResult(chunks.size(),
			HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8))));
	}

	/**
	 * 按完整租户、知识库、文档和版本边界删除受管向量。
	 *
	 * @param metadata 受管文档身份
	 */
	public void deleteManagedVersion(ManagedDocumentMetadata metadata) {
		validateManagedMetadata(metadata);
		this.vectorStore.delete(buildManagedVersionFilter(metadata));
	}

	/**
	 * 使用受限 Tika 读取资源并完成两阶段分片校验。
	 *
	 * @param resource 文档资源
	 * @return 合格最终分片
	 * @throws IllegalArgumentException 文本或分片超出资源边界时抛出 PARAM_ERROR
	 */
	private List<Document> readAndSplitResource(Resource resource) {
		List<Document> documents;
		try {
			// Tika 在解析阶段直接限制输出字符数，避免大文档先占满堆内存。
			documents = new TikaDocumentReader(resource,
				new BodyContentHandler(MAX_EXTRACTED_TEXT_CHARS),
				ExtractedTextFormatter.defaults()).read();
		}
		catch (RuntimeException ex) {
			if (isTextLimitExceeded(ex)) {
				throw new IllegalArgumentException(
					"文档解析文本超过 " + MAX_EXTRACTED_TEXT_CHARS + " 个字符", ex);
			}
			throw ex;
		}
		return splitAndValidate(documents);
	}

	/**
	 * 为分片写入稳定证据元数据并按批入库。
	 *
	 * @param chunks   合格分片
	 * @param metadata 受管文档身份
	 * @throws IllegalArgumentException 文档没有可入库文本时抛出 PARAM_ERROR
	 */
	private void addManagedMetadataAndStore(List<Document> chunks, ManagedDocumentMetadata metadata) {
		if (chunks.isEmpty()) {
			throw new IllegalArgumentException("文档未提取到可入库文本");
		}
		for (int index = 0; index < chunks.size(); index++) {
			Document chunk = chunks.get(index);
			chunk.getMetadata().put("ent_code", metadata.entCode());
			chunk.getMetadata().put("knowledge_base_id", metadata.knowledgeBaseId());
			chunk.getMetadata().put("document_id", metadata.documentId());
			chunk.getMetadata().put("document_version", metadata.documentVersion());
			chunk.getMetadata().put("chunk_id", UUID.randomUUID().toString());
			chunk.getMetadata().put("chunk_index", index);
			chunk.getMetadata().put("source", metadata.sourceName());
			chunk.getMetadata().put(EmbeddingModelMetadata.METADATA_KEY,
				EmbeddingModelMetadata.CURRENT_MODEL_ID);
		}
		try {
			for (int start = 0; start < chunks.size(); start += VECTOR_WRITE_BATCH_SIZE) {
				int end = Math.min(start + VECTOR_WRITE_BATCH_SIZE, chunks.size());
				this.vectorStore.add(List.copyOf(chunks.subList(start, end)));
			}
		}
		catch (RuntimeException ex) {
			try {
				deleteManagedVersion(metadata);
			}
			catch (RuntimeException cleanupEx) {
				log.warn("受管文档导入失败后清理残留分片失败: documentId={}, version={}, error={}",
					metadata.documentId(), metadata.documentVersion(), cleanupEx.getMessage());
			}
			throw ex;
		}
	}

	/**
	 * 构建受管文档版本的精确向量过滤条件。
	 *
	 * @param metadata 受管文档身份
	 * @return 向量过滤表达式
	 */
	private Filter.Expression buildManagedVersionFilter(ManagedDocumentMetadata metadata) {
		FilterExpressionBuilder builder = new FilterExpressionBuilder();
		return builder.and(
			builder.eq("ent_code", metadata.entCode()),
			builder.and(
				builder.eq("knowledge_base_id", metadata.knowledgeBaseId()),
				builder.and(
					builder.eq("document_id", metadata.documentId()),
					builder.eq("document_version", metadata.documentVersion())))).build();
	}

	/**
	 * 校验受管文档身份完整且属于当前租户。
	 *
	 * @param metadata 受管文档身份
	 * @throws IllegalArgumentException 身份字段缺失时抛出 PARAM_ERROR
	 * @throws IllegalStateException 租户不一致时抛出 BIZ_ERROR
	 */
	private void validateManagedMetadata(ManagedDocumentMetadata metadata) {
		if (metadata == null || metadata.knowledgeBaseId() == null || metadata.knowledgeBaseId().isBlank()
				|| metadata.documentId() == null || metadata.documentId().isBlank()
				|| metadata.sourceName() == null || metadata.sourceName().isBlank()
				|| metadata.documentVersion() <= 0) {
			throw new IllegalArgumentException("受管文档身份不完整");
		}
		if (!TenantContext.requireEntCode().equals(metadata.entCode())) {
			throw new IllegalStateException("受管文档租户边界不一致");
		}
	}

	/**
	 * 创建 SHA-256 摘要计算器。
	 *
	 * @return SHA-256 摘要计算器
	 */
	private static MessageDigest createSha256Digest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("当前运行环境不支持 SHA-256", ex);
		}
	}

}
