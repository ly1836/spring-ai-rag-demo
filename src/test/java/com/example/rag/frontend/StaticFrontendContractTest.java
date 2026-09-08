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
			.contains("app.js?v=35")
			.doesNotContain("cdn.jsdelivr")
			.doesNotContain("unpkg.com");
		assertThat(indexHtml.indexOf("vendor/echarts.min.js"))
			.isLessThan(indexHtml.indexOf("echarts-custom-word-cloud.auto.js"));
		assertThat(indexHtml.indexOf("echarts-custom-liquid-fill.auto.js"))
			.isLessThan(indexHtml.indexOf("chart-adapter.js"));
		assertThat(indexHtml.indexOf("chart-adapter.js"))
			.isLessThan(indexHtml.indexOf("app.js?v=35"));
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

	/**
	 * 验证知识库、文档管理和检索统一复用 apiCall 并携带当前知识库。
	 *
	 * @throws Exception 读取静态资源失败时抛出
	 */
	@Test
	public void shouldManageKnowledgeBasesAndDocumentsThroughUnifiedApi() throws Exception {
		String indexHtml = Files.readString(Path.of("src/main/resources/static/index.html"));
		String appJs = Files.readString(Path.of("src/main/resources/static/app.js"));
		// 单独截取加载函数的失败分支，避免顶层初始值让契约检查误判通过。
		int loadStart = appJs.indexOf("async function loadKnowledgeBases");
		int loadEnd = appJs.indexOf("function selectKnowledgeBase", loadStart);
		String loadFunction = appJs.substring(loadStart, loadEnd);
		int failureStart = loadFunction.indexOf("} catch (e) {");
		int resetStart = appJs.indexOf("function resetKnowledgeBaseState");
		int resetEnd = appJs.indexOf("async function loadKnowledgeBases", resetStart);
		String resetFunction = appJs.substring(resetStart, resetEnd);

		assertThat(indexHtml)
			.contains("id=\"knowledgeBaseSelect\"")
			.contains("id=\"knowledgeDocuments\"")
			.contains("style.css?v=13")
			.contains("app.js?v=35");
		assertThat(appJs)
			.contains("apiCall(API + '/knowledge-bases')")
			.contains("apiPost(API + '/knowledge-bases'")
			.contains("apiPut(API + '/knowledge-bases/'")
			.contains("apiDelete(API + '/knowledge-bases/'")
			.contains("function replaceKnowledgeDocument(documentId)")
			.contains("function deleteKnowledgeDocument(documentId)")
			.contains("function isCurrentKnowledgeBaseActive()")
			.contains("documentItem.requiresReindex === true")
			.contains("需重新导入")
			.contains("当前嵌入模型已更新，请重新上传原文件后再用于问答")
			.contains("await loadKnowledgeBases(selected.knowledgeBaseId)")
			.contains("knowledgeBaseId: requestedKnowledgeBaseId")
			.contains("params.set('knowledgeBaseId', currentKnowledgeBaseId)")
			.doesNotContain("option.disabled = item.status !== 'active'");
		assertThat(failureStart).isNotNegative();
		assertThat(loadFunction.substring(failureStart))
			// 知识库列表失败时必须丢弃旧选择，避免后续请求继续使用过期状态。
			.contains("resetKnowledgeBaseState('知识库加载失败'");
		assertThat(resetFunction)
			// 状态清理由统一方法负责，租户切换和加载失败保持相同行为。
			.contains("knowledgeBases = [];", "currentKnowledgeBaseId = '';",
					"unavailableHistoricalKnowledgeBaseId = '';");
	}

	/**
	 * 验证 SSE 引用和图表只在 done 后确认渲染，提前断连会进入失败路径。
	 *
	 * @throws Exception 读取静态资源失败时抛出
	 */
	@Test
	public void shouldConfirmCitationsAndChartsOnlyAfterDone() throws Exception {
		String appJs = Files.readString(Path.of("src/main/resources/static/app.js"));
		int citationBranch = appJs.indexOf("parsed.event === 'citations'");
		int doneBranch = appJs.indexOf("parsed.event === 'done'", citationBranch);
		int errorBranch = appJs.indexOf("parsed.event === 'error'", doneBranch);

		assertThat(citationBranch).isNotNegative();
		assertThat(doneBranch).isGreaterThan(citationBranch);
		assertThat(errorBranch).isGreaterThan(doneBranch);
		assertThat(appJs.substring(citationBranch, doneBranch))
			.contains("state.pendingCitations")
			.doesNotContain("renderMessageCitations(");
		assertThat(appJs.substring(doneBranch, errorBranch))
			.contains("renderMessageCitations(messageHandle, state.pendingCitations)")
			.contains("renderMessageChart(messageHandle, state.pendingChart)");
		assertThat(appJs)
			.contains("if (!streamState.done)")
			.contains("回答连接提前结束，请重试")
			.contains("state.pendingCitations = null")
			.contains("state.pendingChart = null");
	}

	/**
	 * 验证文档、引用和错误等动态值不会未经转义进入 HTML。
	 *
	 * @throws Exception 读取静态资源失败时抛出
	 */
	@Test
	public void shouldRenderUntrustedKnowledgeAndCitationValuesAsText() throws Exception {
		String appJs = Files.readString(Path.of("src/main/resources/static/app.js"));

		assertThat(appJs)
			.contains("title.textContent = documentItem.sourceName")
			.contains("error.textContent = documentItem.errorMessage")
			.contains("summary.textContent = '['")
			.contains("excerpt.textContent = citation.excerpt")
			.contains("querySelector('p').textContent = '✅ ' + selectedFile.name")
			.contains("escapeHtml(r.source || '-')")
			.contains("escapeHtml(e.message || '未知错误')")
			.doesNotContain("innerHTML = '&#9989; ' + selectedFile.name")
			.doesNotContain("+ (r.source || '-') +");
	}

	/**
	 * 验证历史续聊恢复最后一次成功知识库，不可用时禁止静默回退发送。
	 *
	 * @throws Exception 读取静态资源失败时抛出
	 */
	@Test
	public void shouldRestoreHistoricalKnowledgeBaseWithoutSilentFallback() throws Exception {
		String appJs = Files.readString(Path.of("src/main/resources/static/app.js"));

		assertThat(appJs)
			.contains("const lastKnowledgeMessage = messages.slice().reverse().find")
			.contains("await loadKnowledgeBases(lastKnowledgeMessage ? lastKnowledgeMessage.knowledgeBaseId : undefined, true)")
			.contains("unavailableHistoricalKnowledgeBaseId")
			.contains("if (mode !== 'data' && (!currentKnowledgeBaseId || !isCurrentKnowledgeBaseActive()))")
			.contains("renderMessageCitations(messageHandle, m.citations)")
			.contains("renderMessageCitations({ wrapper: historyItems[index], meta: null }, message.citations)");
	}

	/**
	 * 验证自动模式与知识模式都携带当前知识库，数据模式不要求知识库。
	 *
	 * @throws Exception 读取静态资源失败时抛出
	 */
	@Test
	public void shouldBindManagedKnowledgeBaseInAutoAndKnowledgeModes() throws Exception {
		String appJs = Files.readString(Path.of("src/main/resources/static/app.js"));

		assertThat(appJs)
			.contains("if (mode !== 'data' && (!currentKnowledgeBaseId || !isCurrentKnowledgeBaseActive()))")
			.contains("if (mode !== 'data' && currentKnowledgeBaseId)")
			.contains("params.set('knowledgeBaseId', currentKnowledgeBaseId)")
			.contains("state.knowledgeBaseId = payload.knowledgeBaseId || state.knowledgeBaseId || ''");
	}

	/**
	 * 验证自定义租户变更会重新加载知识库，且过期响应不能覆盖当前租户状态。
	 *
	 * @throws Exception 读取静态资源失败时抛出
	 */
	@Test
	public void shouldKeepKnowledgeBaseStateBoundToCurrentTenant() throws Exception {
		String indexHtml = Files.readString(Path.of("src/main/resources/static/index.html"));
		String appJs = Files.readString(Path.of("src/main/resources/static/app.js"));
		int loadStart = appJs.indexOf("async function loadKnowledgeBases");
		int loadEnd = appJs.indexOf("function selectKnowledgeBase", loadStart);
		String loadFunction = appJs.substring(loadStart, loadEnd);

		assertThat(indexHtml).contains("onchange=\"onCustomEntCodeChange()\"");
		assertThat(appJs)
			.contains("let knowledgeBaseLoadSequence = 0;")
			.contains("function onCustomEntCodeChange()")
			.contains("resetKnowledgeBaseState('请输入自定义租户编码'")
			.doesNotContain("value.trim() || 'ENT001'");
		assertThat(loadFunction)
			.contains("const requestedEntCode = getEntCode();")
			.contains("const requestSequence = ++knowledgeBaseLoadSequence;")
			.contains("requestSequence !== knowledgeBaseLoadSequence")
			.contains("requestedEntCode !== getEntCode()");
		assertThat(appJs)
			.contains("const requestedKnowledgeBaseId = currentKnowledgeBaseId;")
			.contains("requestedKnowledgeBaseId !== currentKnowledgeBaseId");
	}

	/**
	 * 验证文档搜索只接受当前租户和知识库中的最后一次响应。
	 *
	 * @throws Exception 读取静态资源失败时抛出
	 */
	@Test
	public void shouldIgnoreStaleDocumentSearchResponsesAfterScopeSwitch() throws Exception {
		String appJs = Files.readString(Path.of("src/main/resources/static/app.js"));
		int searchStart = appJs.indexOf("async function searchDocs");
		int searchEnd = appJs.indexOf("function setMode", searchStart);
		String searchFunction = appJs.substring(searchStart, searchEnd);

		assertThat(searchStart).isNotNegative();
		assertThat(searchEnd).isGreaterThan(searchStart);
		assertThat(appJs)
			.contains("let documentSearchSequence = 0;")
			.contains("documentSearchSequence++;");
		assertThat(searchFunction)
			.contains("const requestedEntCode = getEntCode();")
			.contains("const requestedKnowledgeBaseId = currentKnowledgeBaseId;")
			.contains("const requestSequence = ++documentSearchSequence;")
			.contains("knowledgeBaseId: requestedKnowledgeBaseId")
			.contains("requestSequence !== documentSearchSequence")
			.contains("requestedEntCode !== getEntCode()")
			.contains("requestedKnowledgeBaseId !== currentKnowledgeBaseId");
	}

	/**
	 * 验证文档列表只接受当前租户和知识库中的最后一次响应。
	 *
	 * @throws Exception 读取静态资源失败时抛出
	 */
	@Test
	public void shouldIgnoreStaleKnowledgeDocumentListResponses() throws Exception {
		String appJs = Files.readString(Path.of("src/main/resources/static/app.js"));
		int loadStart = appJs.indexOf("async function loadKnowledgeDocuments");
		int loadEnd = appJs.indexOf("function createKnowledgeDocumentCard", loadStart);
		String loadFunction = appJs.substring(loadStart, loadEnd);

		assertThat(loadStart).isNotNegative();
		assertThat(loadEnd).isGreaterThan(loadStart);
		assertThat(appJs)
			.contains("let knowledgeDocumentLoadSequence = 0;")
			.contains("knowledgeDocumentLoadSequence++;");
		assertThat(loadFunction)
			.contains("const requestSequence = ++knowledgeDocumentLoadSequence;")
			.contains("requestSequence !== knowledgeDocumentLoadSequence")
			.contains("requestedEntCode !== getEntCode()")
			.contains("requestedKnowledgeBaseId !== currentKnowledgeBaseId");
	}

}
