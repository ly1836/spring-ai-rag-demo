package com.example.rag.tool.dynamic;

import com.example.rag.dao.entity.LlmToolEntity;
import com.alibaba.fastjson2.JSON;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * 数据库动态 ToolCallback 实现。
 */
public class DatabaseToolCallback implements ToolCallback {

	/** 动态 Tool 配置。 */
	private final LlmToolEntity tool;

	/** 数据库动态 Tool 执行器。 */
	private final DatabaseToolExecutor executor;

	/** Spring AI Tool 定义。 */
	private final ToolDefinition toolDefinition;

	/**
	 * 创建数据库动态 ToolCallback。
	 *
	 * @param tool         动态 Tool 配置
	 * @param executor     数据库动态 Tool 执行器
	 */
	public DatabaseToolCallback(LlmToolEntity tool, DatabaseToolExecutor executor) {
		this.tool = tool;
		this.executor = executor;
		this.toolDefinition = DefaultToolDefinition.builder()
			.name(tool.getToolName())
			.description(tool.getToolDesc())
			.inputSchema(tool.getInputSchema())
			.build();
	}

	/**
	 * 返回 Spring AI Tool 定义。
	 *
	 * @return Tool 定义
	 */
	@Override
	public ToolDefinition getToolDefinition() {
		return toolDefinition;
	}

	/**
	 * 返回默认 Tool 元数据。
	 *
	 * @return Tool 元数据
	 */
	@Override
	public ToolMetadata getToolMetadata() {
		return ToolMetadata.builder().build();
	}

	/**
	 * 执行数据库动态 Tool。
	 *
	 * @param toolInput Tool 参数 JSON
	 * @return Tool 查询结果 JSON
	 */
	@Override
	public String call(String toolInput) {
		return call(toolInput, null);
	}

	/**
	 * 执行数据库动态 Tool。
	 *
	 * @param toolInput   Tool 参数 JSON
	 * @param toolContext Tool 上下文
	 * @return Tool 查询结果 JSON
	 */
	@Override
	public String call(String toolInput, ToolContext toolContext) {
		DatabaseToolResult result = executor.execute(tool, toolInput);
		try {
			return JSON.toJSONString(result.rows());
		}
		catch (Exception ex) {
			throw new IllegalStateException("工具结果序列化失败", ex);
		}
	}

}
