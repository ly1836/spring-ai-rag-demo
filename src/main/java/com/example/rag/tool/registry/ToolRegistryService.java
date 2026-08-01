package com.example.rag.tool.registry;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.example.rag.chat.chart.capture.ToolResultRecorder;
import com.example.rag.dao.entity.LlmToolEntity;
import com.example.rag.dao.mapper.LlmToolMapper;
import com.example.rag.tool.BaseTool;
import com.example.rag.tool.dynamic.DatabaseToolCallbackFactory;
import com.example.rag.tool.dynamic.SqlToolValidator;
import com.example.rag.tool.trace.LoggingToolCallback;
import com.example.rag.tool.trace.ToolCallLogService;
import com.example.rag.tool.trace.ToolCallRecorder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * LLM Tool 快照注册服务。
 */
@Service
public class ToolRegistryService {

	private static final Logger log = LoggerFactory.getLogger(ToolRegistryService.class);

	/** 代码内置 Tool 列表。 */
	private final List<BaseTool> codeTools;

	/** 动态 Tool 定义 Mapper。 */
	private final LlmToolMapper llmToolMapper;

	/** 数据库动态 ToolCallback 工厂。 */
	private final DatabaseToolCallbackFactory databaseToolCallbackFactory;

	/** Tool 调用聚合记录器。 */
	private final ToolCallRecorder toolCallRecorder;

	/** Tool 调用流水服务。 */
	private final ToolCallLogService toolCallLogService;

	/** 动态 SQL Tool 校验器。 */
	private final SqlToolValidator sqlToolValidator;

	/** 业务 Tool 结果记录器。 */
	private final ToolResultRecorder toolResultRecorder;

	/** 当前 Tool 快照引用。 */
	private final AtomicReference<ToolSnapshot> currentSnapshot;

	/**
	 * 创建 LLM Tool 快照注册服务。
	 *
	 * @param codeTools                   代码内置 Tool 列表
	 * @param llmToolMapper               动态 Tool 定义 Mapper
	 * @param databaseToolCallbackFactory 数据库动态 ToolCallback 工厂
	 * @param toolCallRecorder            Tool 调用聚合记录器
	 * @param toolCallLogService          Tool 调用流水服务
	 * @param sqlToolValidator            动态 SQL Tool 校验器
	 * @param toolResultRecorder          业务 Tool 结果记录器
	 */
	public ToolRegistryService(List<BaseTool> codeTools, LlmToolMapper llmToolMapper,
			DatabaseToolCallbackFactory databaseToolCallbackFactory, ToolCallRecorder toolCallRecorder,
			ToolCallLogService toolCallLogService, SqlToolValidator sqlToolValidator,
			ToolResultRecorder toolResultRecorder) {
		this.codeTools = List.copyOf(codeTools);
		this.llmToolMapper = llmToolMapper;
		this.databaseToolCallbackFactory = databaseToolCallbackFactory;
		this.toolCallRecorder = toolCallRecorder;
		this.toolCallLogService = toolCallLogService;
		this.sqlToolValidator = sqlToolValidator;
		this.toolResultRecorder = toolResultRecorder;
		List<ToolCallback> callbacks = buildCodeToolCallbacks();
		this.currentSnapshot = new AtomicReference<>(new ToolSnapshot(1L, callbacks, describe(callbacks)));
	}

	/**
	 * 应用启动完成后刷新数据库动态 Tool。
	 */
	@EventListener(ApplicationReadyEvent.class)
	public void refreshAfterStartup() {
		try {
			refresh();
		}
		catch (Exception ex) {
			log.warn("启动刷新动态 Tool 失败，保留代码内置 Tool: {}", ex.getMessage());
		}
	}

	/**
	 * 获取当前 Tool 快照。
	 *
	 * @return 当前 Tool 快照
	 */
	public ToolSnapshot currentSnapshot() {
		return currentSnapshot.get();
	}

	/**
	 * 从数据库重新加载动态 Tool 并发布新快照。
	 *
	 * @return 新的 Tool 快照
	 */
	public synchronized ToolSnapshot refresh() {
		List<ToolCallback> codeCallbacks = buildCodeToolCallbacks();
		List<ToolCallback> databaseCallbacks = new ArrayList<>();
		Set<String> loadedNames = new LinkedHashSet<>();
		for (ToolCallback callback : codeCallbacks) {
			loadedNames.add(callback.getToolDefinition().name());
		}
		for (LlmToolEntity tool : llmToolMapper.selectActiveTools()) {
			try {
				sqlToolValidator.validateTool(tool);
				ToolCallback callback = databaseToolCallbackFactory.create(tool);
				String toolName = callback.getToolDefinition().name();
				if (!loadedNames.add(toolName)) {
					log.warn("动态 Tool 名称重复，已跳过，toolName={}", toolName);
					continue;
				}
				databaseCallbacks.add(wrap(callback, "database"));
			}
			catch (Exception ex) {
				log.warn("动态 Tool 加载失败，已跳过，toolName={}，原因: {}", tool.getToolName(), ex.getMessage());
			}
		}
		// 动态 Tool 放在前面，减少与代码兼容 Tool 能力重叠时的误选。
		List<ToolCallback> callbacks = new ArrayList<>(databaseCallbacks);
		callbacks.addAll(codeCallbacks);
		validateUniqueToolNames(callbacks);
		ToolSnapshot previous = currentSnapshot.get();
		ToolSnapshot next = new ToolSnapshot(previous.version() + 1, callbacks, describe(callbacks));
		currentSnapshot.set(next);
		log.info("动态 Tool 已刷新，版本: {}，数量: {}", next.version(), next.callbacks().size());
		return next;
	}

	/**
	 * 构建代码内置 ToolCallback 列表。
	 *
	 * @return 代码内置 ToolCallback 列表
	 */
	public List<ToolCallback> buildCodeToolCallbacks() {
		if (codeTools.isEmpty()) {
			return List.of();
		}
		ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
			.toolObjects(codeTools.toArray())
			.build()
			.getToolCallbacks();
		List<ToolCallback> result = new ArrayList<>();
		for (ToolCallback callback : callbacks) {
			result.add(wrap(callback, "code"));
		}
		return result;
	}

	/**
	 * 包装 ToolCallback，统一追加调用日志。
	 *
	 * @param callback 原始 ToolCallback
	 * @param toolType Tool 来源类型
	 * @return 带日志能力的 ToolCallback
	 */
	public ToolCallback wrap(ToolCallback callback, String toolType) {
		return new LoggingToolCallback(callback, toolType, toolCallRecorder, toolCallLogService, toolResultRecorder);
	}

	/**
	 * 提取 Tool 描述列表。
	 *
	 * @param callbacks ToolCallback 列表
	 * @return Tool 描述列表
	 */
	public List<String> describe(List<ToolCallback> callbacks) {
		return callbacks.stream()
			.map(callback -> callback.getToolDefinition().description())
			.filter(description -> description != null && !description.isBlank())
			.toList();
	}

	/**
	 * 校验 Tool 名称不能重复。
	 *
	 * @param callbacks ToolCallback 列表
	 */
	public void validateUniqueToolNames(List<ToolCallback> callbacks) {
		Set<String> names = new LinkedHashSet<>();
		for (ToolCallback callback : callbacks) {
			String name = callback.getToolDefinition().name();
			if (!names.add(name)) {
				throw new IllegalStateException("Tool 名称重复: " + name);
			}
		}
	}

}
