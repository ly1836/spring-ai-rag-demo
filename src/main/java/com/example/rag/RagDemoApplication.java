package com.example.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ERP 智能助手应用启动类。
 * <p>
 * 整合了两大 AI 能力：
 * 1. Tool Calling — LLM 自动调用 @Tool 方法查询 ERP MySQL 业务数据
 * 2. RAG — 从 PgVector 检索产品手册，为 LLM 提供知识上下文
 */
@SpringBootApplication
public class RagDemoApplication {

	public static void main(String[] args) {
		SpringApplication.run(RagDemoApplication.class, args);
	}

}
