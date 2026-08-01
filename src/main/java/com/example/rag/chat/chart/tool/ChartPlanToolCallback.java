package com.example.rag.chat.chart.tool;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.rag.chat.chart.capture.ToolResultRecorder;
import com.example.rag.chat.chart.compile.ChartCompiler;
import com.example.rag.chat.chart.compile.ChartPlanFactory;
import com.example.rag.chat.chart.model.BusinessToolResult;
import com.example.rag.chat.chart.model.ChartPlan;
import com.example.rag.tool.ToolNames;
import com.example.rag.vo.ChartVO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.stereotype.Component;

/**
 * LLM 图表选择内部 Tool，模型只选择类型和标题，完整规划由后端生成。
 */
@Component
public class ChartPlanToolCallback implements ToolCallback {

	private static final Logger log = LoggerFactory.getLogger(ChartPlanToolCallback.class);

	/** 内部图表选择 Tool 名称。 */
	public static final String TOOL_NAME = ToolNames.CHART_PLAN;

	/** 模型侧图表选择只允许类型和标题字段。 */
	private static final Set<String> SELECTION_FIELDS = Set.of("type", "title");

	/** 图表选择原始 JSON 最大 UTF-8 字节数。 */
	private static final int MAX_INPUT_JSON_BYTES = 2 * 1024;

	/** 图表选择 JSON 最大嵌套深度。 */
	private static final int MAX_INPUT_NESTING_DEPTH = 4;

	/** 图表选择单个对象或数组最大元素数。 */
	private static final int MAX_INPUT_CONTAINER_SIZE = 2;

	/** 图表选择 JSON 最大结构节点数。 */
	private static final int MAX_INPUT_TOTAL_NODES = 8;

	/** 仅开放图表类型和标题的显式 JSON Schema。 */
	private static final String INPUT_SCHEMA = """
		{
		  "type": "object",
		  "additionalProperties": false,
		  "required": ["type", "title"],
		  "properties": {
		    "type": {
		      "type": "string",
		      "enum": %s
		    },
		    "title": {"type": "string", "minLength": 1, "maxLength": 120}
		  }
		}
		""".formatted(JSON.toJSONString(ChartVO.ChartType.codes()));

	/** 图表选择 Tool 描述和图表类型选择原则。 */
	private static final String DESCRIPTION = """
		【用途】
		根据本轮业务查询返回的真实数据，选择本次回答唯一图表的类型和标题。

		【调用条件】
		1. 必须先完成相关业务查询，并确认结果成功、非空且适合可视化。
		2. 必须单独调用本工具，不得与业务查询并行调用。
		3. 业务结果有两行及以上，且包含数值字段或可按状态、类型等分类计数时，必须调用本工具。
		4. 即使最终回答使用 Markdown 表格也不能省略本工具。
		5. 空结果或单条不可比较文本不要调用本工具。

		【填写规则】
		1. 只填写 type 和 title，不得填写来源、字段名、业务数值或渲染配置。
		2. type 从固定图表类型中选择，title 使用简短、明确的业务语言。
		3. 字段绑定、数据转换和安全展示选项由后端自动生成。
		4. 后端会依次检查本轮已捕获的结构化业务数据，只有数据无法满足所选类型时才降级为文本。

		【图表选择】
		- 趋势：line、area、step。
		- 类别比较：bar、pie、donut、funnel。
		- 分布：histogram、boxplot。
		- 关系：scatter、bubble、heatmap、sankey。
		- 层级：sunburst、treemap。
		- 计划、目标或指标：gantt、bullet、gauge、liquid-fill。
		- 多指标：radar、parallel。
		- 文本权重：word-cloud。
		- 增减过程：waterfall。
		""";

	/** 后端图表编译器。 */
	private final ChartCompiler compiler;

	/** 本轮业务 Tool 结果记录器。 */
	private final ToolResultRecorder recorder;

	/** 根据结构化业务数据生成完整内部规划的工厂。 */
	private final ChartPlanFactory planFactory;

	/** Spring AI Tool 定义。 */
	private final ToolDefinition toolDefinition;

	/**
	 * 创建内部图表选择 Tool。
	 *
	 * @param compiler    图表编译器
	 * @param recorder    Tool 结果记录器
	 * @param planFactory 后端图表规划工厂
	 */
	public ChartPlanToolCallback(ChartCompiler compiler, ToolResultRecorder recorder,
			ChartPlanFactory planFactory) {
		this.compiler = compiler;
		this.recorder = recorder;
		this.planFactory = planFactory;
		this.toolDefinition = DefaultToolDefinition.builder()
			.name(TOOL_NAME)
			.description(DESCRIPTION)
			.inputSchema(INPUT_SCHEMA)
			.build();
	}

	/**
	 * 返回内部图表选择 Tool 定义。
	 *
	 * @return Tool 定义
	 */
	@Override
	public ToolDefinition getToolDefinition() {
		return toolDefinition;
	}

	/**
	 * 返回默认 Tool 元数据。
	 *
	 * @return Tool 元数据
	 */
	@Override
	public ToolMetadata getToolMetadata() {
		return ToolMetadata.builder().build();
	}

	/**
	 * 执行图表类型和标题选择。
	 *
	 * @param toolInput 图表选择参数 JSON
	 * @return 精简接受结果
	 */
	@Override
	public String call(String toolInput) {
		return call(toolInput, null);
	}

	/**
	 * 根据模型选择和本轮结构化业务数据生成首个有效图表。
	 *
	 * @param toolInput   图表选择参数 JSON
	 * @param toolContext Tool 上下文
	 * @return 精简接受结果
	 */
	@Override
	public String call(String toolInput, ToolContext toolContext) {
		Map<String, Object> context = toolContext == null || toolContext.getContext() == null
			? Map.of() : toolContext.getContext();
		String traceId = contextValue(context, "traceId");
		String entCode = contextValue(context, "entCode");
		String conversationId = contextValue(context, "conversationId");
		try {
			if (recorder.getChart(traceId, entCode, conversationId) != null) {
				return rejected("本轮已存在图表");
			}
			JSONObject input = parseAndValidateInput(toolInput);
			ChartVO.ChartType type = parseType(input.get("type"));
			String title = parseTitle(input.get("title"));
			List<BusinessToolResult> results = recorder.getResults(traceId, entCode, conversationId);
			if (results.isEmpty()) {
				log.warn("图表选择已拒绝: traceId={}, conversationId={}, 原因=本轮没有结构化业务 Tool 结果",
					traceId, conversationId);
				return rejected("本轮没有结构化业务 Tool 结果");
			}
			List<ChartPlan> candidates = planFactory.createCandidates(type, title, results);
			for (ChartPlan candidate : candidates) {
				try {
					ChartVO.ChartSpec chart = compiler.compile(candidate, results);
					if (recorder.acceptChart(traceId, entCode, conversationId, chart)) {
						return JSON.toJSONString(Map.of("accepted", true, "chartId", chart.chartId()));
					}
					return rejected("本轮已存在图表");
				}
				catch (IllegalArgumentException ex) {
					// 当前来源不满足所选类型时继续尝试本轮其他结构化业务结果。
				}
			}
			log.warn("图表自动生成已降级: traceId={}, conversationId={}, type={}, 原因=业务数据不满足所选类型",
				traceId, conversationId, type.getCode());
			return rejected("本轮业务数据无法满足所选图表类型");
		}
		catch (Exception ex) {
			log.warn("图表选择已拒绝: traceId={}, conversationId={}, 原因=类型或标题不合法",
				traceId, conversationId);
			return rejected("图表类型或标题不合法");
		}
	}

	/**
	 * 从 Tool 上下文读取字符串。
	 *
	 * @param context Tool 上下文
	 * @param key     字段名
	 * @return 字符串值，缺失时为空字符串
	 */
	private String contextValue(Map<String, Object> context, String key) {
		Object value = context.get(key);
		return value == null ? "" : value.toString();
	}

	/**
	 * 构造不包含业务数据且不可重试的拒绝结果。
	 *
	 * @param reason 拒绝原因
	 * @return JSON 结果
	 */
	private String rejected(String reason) {
		return JSON.toJSONString(Map.of("accepted", false, "retryable", false, "reason", reason));
	}

	/**
	 * 在单次解析前后校验图表选择输入。
	 *
	 * @param toolInput 原始图表选择 JSON
	 * @return 已完成资源和字段校验的 JSON 对象
	 */
	private JSONObject parseAndValidateInput(String toolInput) {
		validateRawInput(toolInput);
		JSONObject input = JSON.parseObject(toolInput);
		validateInputStructure(input, 1, new int[] { 0 });
		validateAllowedFields(input);
		return input;
	}

	/**
	 * 在 JSON 解析前校验原始字节数、格式边界和嵌套深度。
	 *
	 * @param toolInput 原始图表选择 JSON
	 */
	private void validateRawInput(String toolInput) {
		if (toolInput == null || toolInput.isBlank()) {
			throw new IllegalArgumentException("图表选择不能为空");
		}
		if (toolInput.getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_JSON_BYTES) {
			throw new IllegalArgumentException("图表选择数据大小超过限制");
		}
		int depth = 0;
		boolean insideString = false;
		boolean escaped = false;
		for (int index = 0; index < toolInput.length(); index++) {
			char current = toolInput.charAt(index);
			if (insideString) {
				if (escaped) {
					escaped = false;
				}
				else if (current == '\\') {
					escaped = true;
				}
				else if (current == '"') {
					insideString = false;
				}
				continue;
			}
			if (current == '"') {
				insideString = true;
			}
			else if (current == '{' || current == '[') {
				if (++depth > MAX_INPUT_NESTING_DEPTH) {
					throw new IllegalArgumentException("图表选择嵌套深度超过限制");
				}
			}
			else if (current == '}' || current == ']') {
				if (--depth < 0) {
					throw new IllegalArgumentException("图表选择不是合法 JSON");
				}
			}
		}
		if (insideString || depth != 0) {
			throw new IllegalArgumentException("图表选择不是合法 JSON");
		}
	}

	/**
	 * 校验解析后 JSON 的容器宽度、嵌套深度和节点总量。
	 *
	 * @param value     当前 JSON 节点
	 * @param depth     当前节点深度
	 * @param nodeCount 已累计节点数
	 */
	private void validateInputStructure(Object value, int depth, int[] nodeCount) {
		if (depth > MAX_INPUT_NESTING_DEPTH) {
			throw new IllegalArgumentException("图表选择嵌套深度超过限制");
		}
		if (++nodeCount[0] > MAX_INPUT_TOTAL_NODES) {
			throw new IllegalArgumentException("图表选择结构节点超过限制");
		}
		if (value instanceof JSONObject object) {
			if (object.size() > MAX_INPUT_CONTAINER_SIZE) {
				throw new IllegalArgumentException("图表选择对象字段数量超过限制");
			}
			for (Object child : object.values()) {
				validateInputStructure(child, depth + 1, nodeCount);
			}
		}
		else if (value instanceof JSONArray array) {
			if (array.size() > MAX_INPUT_CONTAINER_SIZE) {
				throw new IllegalArgumentException("图表选择数组元素数量超过限制");
			}
			for (Object child : array) {
				validateInputStructure(child, depth + 1, nodeCount);
			}
		}
	}

	/**
	 * 拒绝模型提交类型和标题之外的字段。
	 *
	 * @param input 图表选择 JSON
	 */
	private void validateAllowedFields(JSONObject input) {
		if (input == null || !input.keySet().equals(SELECTION_FIELDS)) {
			throw new IllegalArgumentException("图表选择包含未允许字段");
		}
	}

	/**
	 * 解析模型选择的图表类型。
	 *
	 * @param value 原始类型值
	 * @return 图表类型枚举
	 */
	private ChartVO.ChartType parseType(Object value) {
		if (!(value instanceof String code)) {
			throw new IllegalArgumentException("图表类型不合法");
		}
		return ChartVO.ChartType.fromCode(code);
	}

	/**
	 * 解析并校验模型选择的图表标题。
	 *
	 * @param value 原始标题值
	 * @return 安全标题
	 */
	private String parseTitle(Object value) {
		if (!(value instanceof String title) || title.isBlank() || title.length() > 120) {
			throw new IllegalArgumentException("图表标题不合法");
		}
		String lower = title.toLowerCase();
		if (title.contains("<") || title.contains(">") || lower.contains("javascript:")
				|| lower.contains("http://") || lower.contains("https://")) {
			throw new IllegalArgumentException("图表标题不合法");
		}
		return title;
	}

}
