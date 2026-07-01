package com.example.rag.tool.dynamic;

import com.example.rag.dao.entity.LlmToolEntity;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/**
 * 数据库动态 ToolCallback 工厂。
 */
@Component
public class DatabaseToolCallbackFactory {

	/** 数据库动态 Tool 执行器。 */
	private final DatabaseToolExecutor databaseToolExecutor;

	/**
	 * 创建数据库动态 ToolCallback 工厂。
	 *
	 * @param databaseToolExecutor 数据库动态 Tool 执行器
	 */
	public DatabaseToolCallbackFactory(DatabaseToolExecutor databaseToolExecutor) {
		this.databaseToolExecutor = databaseToolExecutor;
	}

	/**
	 * 根据数据库配置创建 ToolCallback。
	 *
	 * @param tool 动态 Tool 配置
	 * @return ToolCallback 实例
	 */
	public ToolCallback create(LlmToolEntity tool) {
		return new DatabaseToolCallback(tool, databaseToolExecutor);
	}

}
