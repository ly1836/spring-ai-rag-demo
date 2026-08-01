package com.example.rag.chat.dto;

/**
 * 文档搜索结果片段。
 *
 * @param text   文档文本
 * @param source 文档来源
 * @param score  相似度分数
 */
public record DocSnippet(String text, String source, Double score) {
}
