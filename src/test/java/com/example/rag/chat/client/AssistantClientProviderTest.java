package com.example.rag.chat.client;

import java.util.List;

import com.example.rag.chat.chart.capture.ToolResultRecorder;
import com.example.rag.chat.chart.compile.ChartCompiler;
import com.example.rag.chat.chart.compile.ChartPlanFactory;
import com.example.rag.chat.chart.compile.ChartPlanValidator;
import com.example.rag.chat.chart.protocol.ChartSpecCodec;
import com.example.rag.chat.chart.tool.ChartPlanToolCallback;
import com.example.rag.tool.dynamic.SqlToolValidator;
import com.example.rag.tool.registry.ToolRegistryService;
import com.example.rag.tool.registry.ToolSnapshot;
import com.example.rag.tool.trace.ToolCallRecorder;
import org.junit.jupiter.api.Test;

import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 智能助手 ChatClient Tool 装配测试。
 */
class AssistantClientProviderTest {

	/**
	 * 验证业务 Tool 与内部图表规划 Tool 同名时在最终装配前被拒绝。
	 */
	@Test
	public void shouldRejectBusinessToolConflictingWithChartPlanningTool() {
		ToolResultRecorder toolResultRecorder = new ToolResultRecorder();
		ToolRegistryService toolRegistryService = new ToolRegistryService(
			List.of(), null, null, new ToolCallRecorder(), null,
			new SqlToolValidator(), toolResultRecorder);
		ChartPlanValidator validator = new ChartPlanValidator();
		ChartPlanToolCallback chartPlanToolCallback = new ChartPlanToolCallback(
			new ChartCompiler(validator, new ChartSpecCodec()), toolResultRecorder,
			new ChartPlanFactory(validator));
		AssistantClientProvider provider = new AssistantClientProvider(
			null, null, toolRegistryService, chartPlanToolCallback);
		ToolSnapshot conflictingSnapshot = new ToolSnapshot(
			1L, List.of(chartPlanToolCallback), List.of());

		assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
			provider, "buildToolCallbacks", conflictingSnapshot))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("Tool 名称重复: " + ChartPlanToolCallback.TOOL_NAME);
	}

}
