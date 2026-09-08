package com.example.rag.knowledge.dto;

import java.util.List;

/**
 * 文档版本晋级结果。
 *
 * @param supersededVersions 已失效的上一可用版本
 * @param chunkCount         新版本分片数
 * @param status             本次完成版本的最终状态
 */
public record DocumentPromotionResult(List<Integer> supersededVersions, int chunkCount, String status) {
}
