package com.example.rag.tool.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.example.rag.chat.chart.capture.ToolResultRecorder;
import com.example.rag.chat.chart.tool.ChartPlanToolCallback;
import com.example.rag.dao.entity.LlmToolEntity;
import com.example.rag.dao.mapper.LlmToolMapper;
import com.example.rag.tool.BaseTool;
import com.example.rag.tool.dynamic.DatabaseToolCallbackFactory;
import com.example.rag.tool.dynamic.DatabaseToolExecutor;
import com.example.rag.tool.dynamic.SqlToolValidator;
import com.example.rag.tool.trace.ToolCallLogService;
import com.example.rag.tool.trace.ToolCallRecorder;
import org.springframework.ai.tool.annotation.Tool;
import org.junit.jupiter.api.Test;

/**
 * LLM Tool 注册服务测试。
 */
public class ToolRegistryServiceTest {

	/**
	 * 验证单个非法数据库 Tool 不影响其他有效 Tool 加载。
	 */
	@Test
	public void shouldSkipInvalidDatabaseToolWhenRefreshing() {
		LlmToolMapper llmToolMapper = mock(LlmToolMapper.class);
		DatabaseToolCallbackFactory factory = new DatabaseToolCallbackFactory(mock(DatabaseToolExecutor.class));
		ToolCallRecorder recorder = new ToolCallRecorder();
		ToolCallLogService logService = mock(ToolCallLogService.class);
		LlmToolEntity validTool = buildTool("query_valid_order", "SELECT * FROM b_sales_order");
		LlmToolEntity invalidTool = buildTool("query_invalid_order", "UPDATE b_sales_order SET status = 1");
		when(llmToolMapper.selectActiveTools()).thenReturn(List.of(validTool, invalidTool));
		ToolRegistryService service = new ToolRegistryService(List.of(), llmToolMapper, factory, recorder,
			logService, new SqlToolValidator(), new ToolResultRecorder());

		ToolSnapshot snapshot = service.refresh();

		assertThat(snapshot.callbacks()).hasSize(1);
		assertThat(snapshot.callbacks().get(0).getToolDefinition().name()).isEqualTo("query_valid_order");
	}

	/**
	 * 验证重复动态 Tool 名称只跳过冲突项，不影响其他动态 Tool 生效。
	 */
	@Test
	public void shouldSkipDuplicateDatabaseToolWhenRefreshing() {
		LlmToolMapper llmToolMapper = mock(LlmToolMapper.class);
		DatabaseToolCallbackFactory factory = new DatabaseToolCallbackFactory(mock(DatabaseToolExecutor.class));
		ToolCallRecorder recorder = new ToolCallRecorder();
		ToolCallLogService logService = mock(ToolCallLogService.class);
		LlmToolEntity firstTool = buildTool("query_duplicate_order", "SELECT * FROM b_sales_order");
		LlmToolEntity secondTool = buildTool("query_duplicate_order", "SELECT * FROM b_purchase_order");
		when(llmToolMapper.selectActiveTools()).thenReturn(List.of(firstTool, secondTool));
		ToolRegistryService service = new ToolRegistryService(List.of(), llmToolMapper, factory, recorder,
			logService, new SqlToolValidator(), new ToolResultRecorder());

		ToolSnapshot snapshot = service.refresh();

		assertThat(snapshot.callbacks()).hasSize(1);
		assertThat(snapshot.callbacks().get(0).getToolDefinition().name()).isEqualTo("query_duplicate_order");
	}

	/**
	 * 验证动态数据库 Tool 排在代码 Tool 前面，提升动态配置命中的优先级。
	 */
	@Test
	public void shouldPlaceDatabaseToolsBeforeCodeToolsWhenRefreshing() {
		LlmToolMapper llmToolMapper = mock(LlmToolMapper.class);
		DatabaseToolCallbackFactory factory = new DatabaseToolCallbackFactory(mock(DatabaseToolExecutor.class));
		ToolCallRecorder recorder = new ToolCallRecorder();
		ToolCallLogService logService = mock(ToolCallLogService.class);
		LlmToolEntity databaseTool = buildTool("query_dynamic_sales_orders", "SELECT * FROM b_sales_order");
		when(llmToolMapper.selectActiveTools()).thenReturn(List.of(databaseTool));
		ToolRegistryService service = new ToolRegistryService(List.of(new TestSalesTool()), llmToolMapper, factory,
			recorder, logService, new SqlToolValidator(), new ToolResultRecorder());

		ToolSnapshot snapshot = service.refresh();

		assertThat(snapshot.callbacks()).extracting(callback -> callback.getToolDefinition().name())
			.containsExactly("query_dynamic_sales_orders", "getSalesOrders");
	}

	/**
	 * 构造测试用动态 Tool 配置。
	 *
	 * @param toolName    Tool 名称
	 * @param sqlTemplate SQL 模板
	 * @return 动态 Tool 配置
	 */
	private LlmToolEntity buildTool(String toolName, String sqlTemplate) {
		LlmToolEntity tool = new LlmToolEntity();
		tool.setToolName(toolName);
		tool.setToolDesc("查询测试数据");
		tool.setInputSchema("""
			{
			  "type": "object",
			  "properties": {}
			}
			""");
		tool.setSqlTemplate(sqlTemplate);
		tool.setResultLimit(50);
		tool.setStatus("active");
		return tool;
	}

	/**
	 * 测试用代码内置销售 Tool。
	 */
	private static final class TestSalesTool extends BaseTool {

		/**
		 * 创建测试用代码 Tool。
		 */
		private TestSalesTool() {
			super(null);
		}

		/**
		 * 查询销售订单。
		 *
		 * @return 测试数据
		 */
		@Tool(description = "根据客户名称查询销售订单列表")
		public List<Map<String, Object>> getSalesOrders() {
			return List.of();
		}

	}

	/**
	 * 验证内部图表规划 Tool 不会进入业务 Tool 快照和统计范围。
	 */
	@Test
	public void shouldExcludeChartPlanningToolFromBusinessSnapshot() {
		LlmToolMapper llmToolMapper = mock(LlmToolMapper.class);
		when(llmToolMapper.selectActiveTools()).thenReturn(List.of());
		ToolRegistryService service = new ToolRegistryService(List.of(new TestSalesTool()), llmToolMapper,
			new DatabaseToolCallbackFactory(mock(DatabaseToolExecutor.class)), new ToolCallRecorder(),
			mock(ToolCallLogService.class), new SqlToolValidator(), new ToolResultRecorder());

		ToolSnapshot snapshot = service.refresh();

		assertThat(snapshot.callbacks()).extracting(callback -> callback.getToolDefinition().name())
			.containsExactly("getSalesOrders")
			.doesNotContain(ChartPlanToolCallback.TOOL_NAME);
	}

}
