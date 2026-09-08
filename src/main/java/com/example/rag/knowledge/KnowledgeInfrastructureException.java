package com.example.rag.knowledge;

/**
 * 知识文档解析、嵌入、向量写入或失败状态保存时发生的基础设施异常。
 */
public class KnowledgeInfrastructureException extends RuntimeException {

	/**
	 * 创建知识文档基础设施异常。
	 *
	 * @param message 对外使用的安全错误说明
	 * @param cause   原始异常
	 */
	public KnowledgeInfrastructureException(String message, Throwable cause) {
		super(message, cause);
	}

}
