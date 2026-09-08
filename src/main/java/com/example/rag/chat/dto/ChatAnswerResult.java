package com.example.rag.chat.dto;

import java.util.List;

import com.example.rag.vo.ChartVO;
import com.example.rag.vo.ChatVO;

/**
 * 非流式回答内部结果。
 *
 * @param answer          回答文本
 * @param chart           可空图表
 * @param knowledgeBaseId 实际知识库 ID
 * @param citations       经验证的引用
 * @param ragDocCount     合格召回分片数
 */
public record ChatAnswerResult(String answer, ChartVO.ChartSpec chart, String knowledgeBaseId,
		List<ChatVO.CitationResponse> citations, int ragDocCount) {

	/**
	 * 兼容不使用知识库证据的回答结果。
	 *
	 * @param answer 回答文本
	 * @param chart  可空图表
	 */
	public ChatAnswerResult(String answer, ChartVO.ChartSpec chart) {
		this(answer, chart, null, List.of(), 0);
	}

}
