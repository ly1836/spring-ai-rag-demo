package com.example.rag.chat;

import java.lang.reflect.Constructor;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.example.rag.billing.BillingService;
import com.example.rag.chat.chart.capture.ToolResultRecorder;
import com.example.rag.chat.chart.compile.ChartCompiler;
import com.example.rag.chat.chart.compile.ChartPlanFactory;
import com.example.rag.chat.chart.compile.ChartPlanValidator;
import com.example.rag.chat.chart.protocol.ChartSpecCodec;
import com.example.rag.chat.chart.selection.ChartSelectionService;
import com.example.rag.chat.chart.tool.ChartPlanToolCallback;
import com.example.rag.chat.client.AssistantClientProvider;
import com.example.rag.chat.guard.BusinessDataTurnGuard;
import com.example.rag.chat.lifecycle.AssistantLifecycleService;
import com.example.rag.chat.output.AssistantAnswerSanitizer;
import com.example.rag.conversation.ChatHistoryService;
import com.example.rag.tool.registry.ToolRegistryService;
import com.example.rag.tool.trace.ToolCallLogService;
import com.example.rag.tool.trace.ToolCallRecorder;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 智能助手相关 Spring Bean 构造器依赖测试。
 */
class AssistantBeanDependencyTest {

	/** 本次调整涉及的 Spring Bean 类型。 */
	private static final Set<Class<?>> FEATURE_BEANS = Set.of(
		ErpAssistantService.class,
		AssistantClientProvider.class,
		AssistantLifecycleService.class,
		ModelRegistry.class,
		ToolRegistryService.class,
		ChatHistoryService.class,
		BillingService.class,
		ToolCallRecorder.class,
		ToolCallLogService.class,
		ToolResultRecorder.class,
		BusinessDataTurnGuard.class,
		ChartPlanToolCallback.class,
		ChartSelectionService.class,
		ChartCompiler.class,
		ChartPlanFactory.class,
		ChartPlanValidator.class,
		ChartSpecCodec.class,
		AssistantAnswerSanitizer.class);

	/**
	 * 验证本次拆分后的构造器注入图不存在循环依赖。
	 */
	@Test
	public void shouldKeepAssistantBeanDependenciesAcyclic() {
		Map<Class<?>, Set<Class<?>>> graph = this.buildDependencyGraph();

		assertThat(this.findCycle(graph))
			.as("Spring Bean 构造器依赖不应形成闭环")
			.isEmpty();
	}

	/**
	 * 从构造器参数构建本次功能 Bean 的有向依赖图。
	 *
	 * @return Bean 依赖图
	 */
	private Map<Class<?>, Set<Class<?>>> buildDependencyGraph() {
		Map<Class<?>, Set<Class<?>>> graph = new LinkedHashMap<>();
		for (Class<?> beanType : FEATURE_BEANS) {
			Set<Class<?>> dependencies = new LinkedHashSet<>();
			for (Constructor<?> constructor : beanType.getDeclaredConstructors()) {
				Arrays.stream(constructor.getParameterTypes())
					.filter(FEATURE_BEANS::contains)
					.forEach(dependencies::add);
			}
			graph.put(beanType, dependencies);
		}
		return graph;
	}

	/**
	 * 在 Bean 依赖图中查找首个循环路径。
	 *
	 * @param graph Bean 依赖图
	 * @return 循环路径；无环时为空
	 */
	private String findCycle(Map<Class<?>, Set<Class<?>>> graph) {
		Set<Class<?>> visited = new LinkedHashSet<>();
		Set<Class<?>> visiting = new LinkedHashSet<>();
		Deque<Class<?>> path = new ArrayDeque<>();
		for (Class<?> beanType : graph.keySet()) {
			String cycle = this.findCycle(beanType, graph, visited, visiting, path);
			if (!cycle.isEmpty()) {
				return cycle;
			}
		}
		return "";
	}

	/**
	 * 深度优先检查单个 Bean 的依赖路径。
	 *
	 * @param current  当前 Bean
	 * @param graph    Bean 依赖图
	 * @param visited  已完成检查的 Bean
	 * @param visiting 当前递归路径中的 Bean
	 * @param path     当前依赖路径
	 * @return 循环路径；无环时为空
	 */
	private String findCycle(Class<?> current, Map<Class<?>, Set<Class<?>>> graph,
			Set<Class<?>> visited, Set<Class<?>> visiting, Deque<Class<?>> path) {
		if (visiting.contains(current)) {
			return this.formatCycle(path, current);
		}
		if (visited.contains(current)) {
			return "";
		}
		visiting.add(current);
		path.addLast(current);
		for (Class<?> dependency : graph.getOrDefault(current, Set.of())) {
			String cycle = this.findCycle(dependency, graph, visited, visiting, path);
			if (!cycle.isEmpty()) {
				return cycle;
			}
		}
		path.removeLast();
		visiting.remove(current);
		visited.add(current);
		return "";
	}

	/**
	 * 将循环依赖路径格式化为可读文本。
	 *
	 * @param path     当前依赖路径
	 * @param repeated 重复出现的 Bean
	 * @return 格式化后的循环路径
	 */
	private String formatCycle(Deque<Class<?>> path, Class<?> repeated) {
		StringBuilder cycle = new StringBuilder();
		boolean include = false;
		for (Class<?> beanType : path) {
			if (beanType.equals(repeated)) {
				include = true;
			}
			if (include) {
				if (!cycle.isEmpty()) {
					cycle.append(" -> ");
				}
				cycle.append(beanType.getSimpleName());
			}
		}
		return cycle.append(" -> ").append(repeated.getSimpleName()).toString();
	}

}
