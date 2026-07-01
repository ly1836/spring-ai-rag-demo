package com.example.rag.tool.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import org.springframework.ai.tool.ToolCallback;

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
			mock(ToolCallLogService.class));
	}

}
