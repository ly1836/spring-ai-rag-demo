package com.example.rag.knowledge.dto;

/**
 * 稳定文档版本键。
 *
 * @param documentId 文档 ID
 * @param version    版本号
 */
public record DocumentVersionKey(String documentId, int version) {
}
