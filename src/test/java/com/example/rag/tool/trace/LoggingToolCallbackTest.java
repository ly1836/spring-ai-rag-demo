package com.example.rag.tool.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import com.example.rag.chat.chart.capture.ToolResultRecorder;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * ToolCallback 调用日志包装器测试。
 */
public class LoggingToolCallbackTest {

	/**
	 * 验证数组结果按行数统计。
	 */
	@Test
	public void shouldCountArrayResultRows() {
		LoggingToolCallback callback = buildCallback();

		int count = callback.countResultRows("[{\"id\":1},{\"id\":2}]");

		assertThat(count).isEqualTo(2);
	}

	/**
	 * 验证 rows 包装结果按 rows 数组统计。
	 */
	@Test
	public void shouldCountWrappedRowsResult() {
		LoggingToolCallback callback = buildCallback();

		int count = callback.countResultRows("{\"rows\":[{\"id\":1},{\"id\":2}]}");

		assertThat(count).isEqualTo(2);
	}

	/**
	 * 构造测试用 ToolCallback 日志包装器。
	 *
	 * @return ToolCallback 日志包装器
	 */
	private LoggingToolCallback buildCallback() {
		return new LoggingToolCallback(mock(ToolCallback.class), "database", new ToolCallRecorder(),
			mock(ToolCallLogService.class), new ToolResultRecorder());
	}

	/**
	 * 验证业务 Tool 成功返回后会捕获结果且不改变原返回值。
	 */
	@Test
	public void shouldCaptureSuccessfulBusinessToolResult() {
		ToolCallback delegate = mock(ToolCallback.class);
		ToolDefinition definition = mock(ToolDefinition.class);
		ToolResultRecorder resultRecorder = new ToolResultRecorder();
		ToolContext context = new ToolContext(Map.of(
			"traceId", "t1", "entCode", "ENT001", "conversationId", "c1"));
		when(definition.name()).thenReturn("query_sales");
		when(delegate.getToolDefinition()).thenReturn(definition);
		when(delegate.call("{}", context)).thenReturn("[{\"amount\":10}]");
		LoggingToolCallback callback = new LoggingToolCallback(delegate, "database",
			new ToolCallRecorder(), mock(ToolCallLogService.class), resultRecorder);

		String result = callback.call("{}", context);

		assertThat(result).isEqualTo("[{\"amount\":10}]");
		assertThat(resultRecorder.getResults("t1", "ENT001", "c1")).hasSize(1);
	}

}
