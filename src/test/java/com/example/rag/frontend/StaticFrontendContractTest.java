package com.example.rag.frontend;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

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

}
