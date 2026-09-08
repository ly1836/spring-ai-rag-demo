package com.example.rag.knowledge.dto;

/**
 * 受管文档向量写入结果。
 *
 * @param chunkCount    入库分片数
 * @param checksumSha256 文件 SHA-256 摘要
 */
public record ManagedDocumentLoadResult(int chunkCount, String checksumSha256) {
}
