package com.example.rag.tool.registry;

import java.util.List;

import org.springframework.ai.tool.ToolCallback;

/**
 * 当前已加载的 Tool 快照。
 *
 * @param version      快照版本号
 * @param callbacks    已注册 ToolCallback 列表
 * @param descriptions Tool 描述列表
 */
public record ToolSnapshot(long version, List<ToolCallback> callbacks, List<String> descriptions) {

	/**
	 * 创建不可变 Tool 快照。
	 *
	 * @param version      快照版本号
	 * @param callbacks    已注册 ToolCallback 列表
	 * @param descriptions Tool 描述列表
	 */
	public ToolSnapshot {
		callbacks = List.copyOf(callbacks);
		descriptions = List.copyOf(descriptions);
	}
}
