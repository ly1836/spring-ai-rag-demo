package com.example.rag.tool.dynamic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;

import com.example.rag.dao.entity.LlmToolEntity;
import org.junit.jupiter.api.Test;

/**
 * 数据库动态 ToolCallback 测试。
 */
public class DatabaseToolCallbackTest {

	/**
	 * 验证动态 Tool 查询结果使用 fastjson 序列化。
	 */
	@Test
	public void shouldSerializeRowsWithFastjson() {
		LlmToolEntity tool = buildTool();
		DatabaseToolExecutor executor = mock(DatabaseToolExecutor.class);
		LinkedHashMap<String, Object> row = new LinkedHashMap<>();
		row.put("id", 1);
		row.put("name", "测试");
		when(executor.execute(tool, "{\"id\":1}")).thenReturn(new DatabaseToolResult(List.of(row)));
		DatabaseToolCallback callback = new DatabaseToolCallback(tool, executor);

		String result = callback.call("{\"id\":1}");

		assertThat(result).isEqualTo("[{\"id\":1,\"name\":\"测试\"}]");
	}

	/**
	 * 验证动态 Tool 执行异常保持原始语义。
	 */
	@Test
	public void shouldPropagateExecutorException() {
		LlmToolEntity tool = buildTool();
		DatabaseToolExecutor executor = mock(DatabaseToolExecutor.class);
		when(executor.execute(tool, "{\"id\":1}")).thenThrow(new IllegalArgumentException("租户不能为空"));
		DatabaseToolCallback callback = new DatabaseToolCallback(tool, executor);

		assertThatThrownBy(() -> callback.call("{\"id\":1}"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("租户不能为空");
	}

	/**
	 * 构造测试用动态 Tool 配置。
	 *
	 * @return 动态 Tool 配置
	 */
	private LlmToolEntity buildTool() {
		LlmToolEntity tool = new LlmToolEntity();
		tool.setToolName("query_test_data");
		tool.setToolDesc("查询测试数据");
		tool.setInputSchema("""
			{
			  "type": "object",
			  "properties": {
			    "id": {"type": "integer"}
			  }
			}
			""");
		tool.setSqlTemplate("SELECT * FROM b_sales_order WHERE id = :id");
		tool.setResultLimit(50);
		tool.setStatus("active");
		return tool;
	}

}
