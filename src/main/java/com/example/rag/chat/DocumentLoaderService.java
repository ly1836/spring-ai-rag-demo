package com.example.rag.chat;

import java.io.InputStream;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.rag.config.TenantContext;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
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
public class DocumentLoaderService {

	private static final Logger log = LoggerFactory.getLogger(DocumentLoaderService.class);

	private final VectorStore vectorStore;

	private final TokenTextSplitter splitter;

	public DocumentLoaderService(VectorStore vectorStore) {
		this.vectorStore = vectorStore;
		this.splitter = TokenTextSplitter.builder()
			.withChunkSize(800)
			.withMinChunkSizeChars(200)
			.withMinChunkLengthToEmbed(50)
			.withMaxNumChunks(200)
			.build();
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
			throw new RuntimeException("Failed to load documents from classpath", e);
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
		List<Document> chunks = splitter.transform(docs);
		addMetadataAndStore(chunks, "user-input");
		return chunks.size();
	}

	/**
	 * 内部方法：读取文件 → 分割 → 存入向量库。
	 * TXT 文件使用 TextReader，其他格式（PDF/Word/Excel 等）使用 TikaDocumentReader。
	 */
	private int loadResource(Resource resource, String filename) {
		log.info("Loading document: {}", filename);

		List<Document> docs;
		String lower = filename.toLowerCase();
		if (lower.endsWith(".txt")) {
			docs = new TextReader(resource).read();
		}
		else {
			docs = new TikaDocumentReader(resource).read();
		}

		List<Document> chunks = splitter.transform(docs);
		addMetadataAndStore(chunks, filename);
		log.info("Loaded {} chunks from {}", chunks.size(), filename);
		return chunks.size();
	}

	/**
	 * 为每个 chunk 添加元数据，然后批量写入向量库。
	 * 元数据包括：
	 * - ent_code: 租户标识（从 TenantContext 获取，确保数据隔离）
	 * - source: 来源文件名
	 */
	private void addMetadataAndStore(List<Document> chunks, String source) {
		String entCode = TenantContext.requireEntCode();
		for (Document chunk : chunks) {
			chunk.getMetadata().put("ent_code", entCode);
			chunk.getMetadata().put("source", source);
		}
		vectorStore.add(chunks);
	}

}
