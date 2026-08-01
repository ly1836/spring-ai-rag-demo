package com.example.rag.frontend;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * 静态前端资源契约测试。
 */
public class StaticFrontendContractTest {

	/**
	 * 验证计费子 Tab 只影响计费区域，不能隐藏工具管理页内容。
	 *
	 * @throws Exception 读取静态资源失败时抛出
	 */
	@Test
	public void shouldScopeBillingTabContentSelector() throws Exception {
		String appJs = Files.readString(Path.of("src/main/resources/static/app.js"));

		assertThat(appJs).contains("document.querySelectorAll('#tabBilling .billing-content')");
		assertThat(appJs).doesNotContain("document.querySelectorAll('.billing-content')");
	}

	/**
	 * 验证工具状态向用户展示中文，但前后端传输仍保持英文状态值。
	 *
	 * @throws Exception 读取静态资源失败时抛出
	 */
	@Test
	public void shouldDisplayToolStatusInChineseButKeepEnglishValues() throws Exception {
		String indexHtml = Files.readString(Path.of("src/main/resources/static/index.html"));
		String appJs = Files.readString(Path.of("src/main/resources/static/app.js"));

		assertThat(indexHtml).contains("<option value=\"active\">启用</option>");
		assertThat(indexHtml).contains("<option value=\"inactive\">停用</option>");
		assertThat(indexHtml).doesNotContain("<option value=\"active\">active</option>");
		assertThat(appJs).contains("formatToolStatus(tool.status)");
		assertThat(appJs).contains("status: document.getElementById('toolStatus').value");
	}

	/**
	 * 验证入参 Schema 字段提供说明和可直接参考的示例数据。
	 *
	 * @throws Exception 读取静态资源失败时抛出
	 */
	@Test
	public void shouldExplainToolInputSchemaWithExample() throws Exception {
		String indexHtml = Files.readString(Path.of("src/main/resources/static/index.html"));
		String appJs = Files.readString(Path.of("src/main/resources/static/app.js"));

		assertThat(indexHtml).contains("入参 Schema（JSON Schema）");
		assertThat(indexHtml).contains("字段名需与 SQL 模板中的参数名一致");
		assertThat(indexHtml).contains("示例：按客户名称查询销售订单");
		assertThat(appJs).contains("DEFAULT_TOOL_INPUT_SCHEMA");
		assertThat(appJs).contains("\"customerName\"");
		assertThat(appJs).contains("客户名称关键字，例如：华东客户");
	}

	/**
	 * 验证 Tool 命中流水来源向用户展示中文，但前后端传输仍保持英文来源值。
	 *
	 * @throws Exception 读取静态资源失败时抛出
	 */
	@Test
	public void shouldDisplayToolCallSourceInChineseButKeepEnglishValues() throws Exception {
		String appJs = Files.readString(Path.of("src/main/resources/static/app.js"));

		assertThat(appJs).contains("formatToolType(log.toolType)");
		assertThat(appJs).contains("if (toolType === 'code') return '代码工具';");
		assertThat(appJs).contains("if (toolType === 'database') return '动态工具';");
	}

	/**
	 * 验证图表脚本按本地 ECharts、官方扩展、适配器和应用代码顺序加载。
	 *
	 * @throws Exception 读取静态资源失败时抛出
	 */
	@Test
	public void shouldLoadLocalChartAssetsInRequiredOrder() throws Exception {
		String indexHtml = Files.readString(Path.of("src/main/resources/static/index.html"));

		assertThat(indexHtml)
			.contains("vendor/echarts.min.js?v=1")
			.contains("vendor/echarts-custom-word-cloud.auto.js?v=1")
			.contains("vendor/echarts-custom-liquid-fill.auto.js?v=1")
			.doesNotContain("echarts-custom-bar-range.auto.js")
			.contains("chart-adapter.js?v=9")
			.contains("app.js?v=26")
			.doesNotContain("cdn.jsdelivr")
			.doesNotContain("unpkg.com");
		assertThat(indexHtml.indexOf("vendor/echarts.min.js"))
			.isLessThan(indexHtml.indexOf("echarts-custom-word-cloud.auto.js"));
		assertThat(indexHtml.indexOf("echarts-custom-liquid-fill.auto.js"))
			.isLessThan(indexHtml.indexOf("chart-adapter.js"));
		assertThat(indexHtml.indexOf("chart-adapter.js"))
			.isLessThan(indexHtml.indexOf("app.js?v=26"));
	}

	/**
	 * 验证聊天前端使用统一类型化 SSE、统一消息图表渲染和实例释放入口。
	 *
	 * @throws Exception 读取静态资源失败时抛出
	 */
	@Test
	public void shouldUseTypedStreamAndReplayMessageCharts() throws Exception {
		String appJs = Files.readString(Path.of("src/main/resources/static/app.js"));
		String styleCss = Files.readString(Path.of("src/main/resources/static/style.css"));

		assertThat(appJs)
			.contains("new URLSearchParams({ question, mode, modelId: currentModelId })")
			.doesNotContain("protocol: 'v2'")
			.contains("function parseSSEEvent(eventText)")
			.contains("function normalizeSSEChunk(text, state, finalChunk)")
			.contains("pendingCarriageReturn: false")
			.contains("parsed.event === 'delta'")
			.contains("parsed.event === 'chart'")
			.contains("parsed.event === 'done'")
			.contains("parsed.event === 'error'")
			.contains("renderStreamError(currentStreamMessage, errorMessage)")
			.contains("state.pendingChart = payload.chart || null")
			.contains("if (state.pendingChart)")
			.contains("if (!streamState.done)")
			.contains("function renderMessageChart(messageHandle, chartSpec)")
			.contains("messageHandle.message.classList.add('has-chart')")
			.contains("renderMessageChart(messageHandle, m.chart)")
			.contains("renderMessageChart({ wrapper: historyItems[index], meta: null }, message.chart)")
			.contains("window.ChartAdapter.disposeWithin(card)")
			.contains("card.remove()")
			.contains("messageHandle.message.classList.remove('has-chart')")
			.contains("window.ChartAdapter.disposeWithin(container)")
			.contains("window.ChartAdapter.resizeWithin(document.body)")
			.doesNotContain("JSON.stringify(chartSpec)");
		assertThat(styleCss).contains(".msg.assistant.has-chart { width: 100%; max-width: 100%; }");
	}

	/**
	 * 验证图表适配器只开放固定 API 和固定安全配置。
	 *
	 * @throws Exception 读取静态资源失败时抛出
	 */
	@Test
	public void shouldKeepChartAdapterDeclarativeAndSafe() throws Exception {
		String adapterJs = Files.readString(Path.of("src/main/resources/static/chart-adapter.js"));

		assertThat(adapterJs)
			.contains("schemaVersion !== '1.0'")
			.contains("SUPPORTED_TYPES")
			.contains("renderMode: 'richText'")
			.contains("render: render")
			.contains("disposeWithin: disposeWithin")
			.contains("resizeWithin: resizeWithin")
			.contains("function renderGanttItem(params, api)")
			.contains("option.xAxis = { type: 'time' }")
			.contains("option.yAxis = { type: 'category', data: categories }")
			.doesNotContain("renderItem: 'barRange'")
			.doesNotContain("optionToContent")
			.doesNotContain("title.link")
			.doesNotContain("eval(")
			.doesNotContain("new Function");
	}

	/**
	 * 使用 Node fixture 验证全部 23 种 ChartSpec 和多系列场景均能构造 ECharts option。
	 *
	 * @throws Exception 执行 fixture 失败时抛出
	 */
	@Test
	public void shouldBuildOptionsForAllChartFixtures() throws Exception {
		Process process;
		try {
			process = new ProcessBuilder(
				"node", "src/test/resources/chart-adapter-fixtures.js")
				.redirectErrorStream(true)
				.start();
		}
		catch (java.io.IOException ex) {
			Assumptions.assumeTrue(false, "当前环境未安装 Node.js");
			return;
		}
		String output = new String(process.getInputStream().readAllBytes(),
			java.nio.charset.StandardCharsets.UTF_8);
		int exitCode = process.waitFor();

		assertThat(exitCode).as(output).isZero();
		assertThat(output).contains("chart-adapter fixtures passed: 33");
	}

	/**
	 * 验证历史详情和续聊只回放接口已有消息，不触发新的问答请求。
	 *
	 * @throws Exception 读取静态资源失败时抛出
	 */
	@Test
	public void shouldReplayHistoryWithoutCallingAssistantAgain() throws Exception {
		String appJs = Files.readString(Path.of("src/main/resources/static/app.js"));
		int historyStart = appJs.indexOf("async function loadMessages");
		int historyEnd = appJs.indexOf("async function continueConversation");
		int historyBodyEnd = appJs.lastIndexOf("/**", historyEnd);
		int continueEnd = appJs.indexOf("async function deleteConversation");

		assertThat(historyStart).isNotNegative();
		assertThat(historyEnd).isGreaterThan(historyStart);
		assertThat(historyBodyEnd).isGreaterThan(historyStart);
		assertThat(continueEnd).isGreaterThan(historyEnd);
		assertThat(appJs.substring(historyStart, historyBodyEnd))
			.contains("API + '/conversations/'")
			.doesNotContain("API + '/ask")
			.doesNotContain("sendQuestion(");
		assertThat(appJs.substring(historyEnd, continueEnd))
			.contains("API + '/conversations/'")
			.doesNotContain("API + '/ask")
			.doesNotContain("sendQuestion(");
	}

}
