package com.example.rag.chat.chart.selection;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.example.rag.chat.chart.capture.ToolResultRecorder;
import com.example.rag.chat.chart.tool.ChartPlanToolCallback;
import com.example.rag.chat.client.AssistantClientProvider;
import com.example.rag.chat.dto.ChartSelection;
import com.example.rag.config.TenantContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Service;

/**
 * 图表类型和标题兜底选择服务，保证 LLM 漏规划时仍由当前模型完成选择。
 */
@Service
public class ChartSelectionService {

	private static final Logger log = LoggerFactory.getLogger(ChartSelectionService.class);

	/** 图表选择上下文最大字符数，避免重复发送超长最终回答。 */
	private static final int MAX_ANSWER_CONTEXT_LENGTH = 4000;

	/** 图表选择响应最大 UTF-8 字节数，限制兼容解析的扫描范围。 */
	private static final int MAX_SELECTION_RESPONSE_BYTES = 2048;

	/** ChatClient 提供器。 */
	private final AssistantClientProvider clientProvider;

	/** 内部图表规划 Tool。 */
	private final ChartPlanToolCallback chartPlanToolCallback;

	/** 本轮业务 Tool 结果记录器。 */
	private final ToolResultRecorder toolResultRecorder;

	/**
	 * 创建图表选择服务。
	 *
	 * @param clientProvider        ChatClient 提供器
	 * @param chartPlanToolCallback 内部图表规划 Tool
	 * @param toolResultRecorder    本轮业务 Tool 结果记录器
	 */
	public ChartSelectionService(AssistantClientProvider clientProvider,
			ChartPlanToolCallback chartPlanToolCallback,
			ToolResultRecorder toolResultRecorder) {
		this.clientProvider = clientProvider;
		this.chartPlanToolCallback = chartPlanToolCallback;
		this.toolResultRecorder = toolResultRecorder;
	}

	/**
	 * 在已有业务结果但没有图表时，由当前模型补选一次类型和标题。
	 *
	 * @param question       用户原始问题
	 * @param answer         已净化的最终业务回答
	 * @param modelId        模型配置 ID
	 * @param modelName      实际模型名称
	 * @param traceId        问答链路 ID
	 * @param conversationId 会话 ID
	 * @param mode           问答模式
	 * @return 图表选择模型响应，未调用或失败时返回 null
	 */
	public ChatResponse ensureChart(String question, String answer, String modelId,
			String modelName, String traceId, String conversationId, String mode) {
		String entCode = TenantContext.requireEntCode();
		if (this.toolResultRecorder.getChart(traceId, entCode, conversationId) != null
				|| this.toolResultRecorder.getResults(traceId, entCode, conversationId).isEmpty()) {
			return null;
		}
		try {
			ChatResponse response = this.clientProvider
				.resolveChartSelectionClient(modelId)
				.prompt()
				.options(ChatOptions.builder().model(modelName).temperature(0.0))
				.user(this.buildSelectionQuestion(question, answer))
				.call()
				.chatResponse();
			ChartSelection selection = this.parseSelection(response);
			if (selection == null || selection.type() == null
					|| selection.title() == null || selection.title().isBlank()) {
				log.warn("图表类型和标题选择失败: traceId={}, conversationId={}, 原因=模型未返回完整选择",
					traceId, conversationId);
				return response;
			}
			Map<String, Object> toolContext = Map.of(
				"traceId", traceId,
				"conversationId", conversationId,
				"mode", mode,
				"model", modelName,
				"entCode", entCode,
				"userId", TenantContext.getUserIdOrDefault());
			this.chartPlanToolCallback.call(JSON.toJSONString(selection), new ToolContext(toolContext));
			if (this.toolResultRecorder.getChart(traceId, entCode, conversationId) == null) {
				log.warn("已有业务数据但所选图表类型不兼容: traceId={}, conversationId={}, type={}",
					traceId, conversationId, selection.type().getCode());
			}
			return response;
		}
		catch (Exception ex) {
			log.warn("图表类型和标题选择失败: traceId={}, conversationId={}, error={}",
				traceId, conversationId, ex.getMessage());
			return null;
		}
	}

	/**
	 * 构造图表选择模型的最小上下文。
	 *
	 * @param question 用户原始问题
	 * @param answer   已净化的最终业务回答
	 * @return 图表选择问题
	 */
	private String buildSelectionQuestion(String question, String answer) {
		String safeAnswer = answer == null ? "" : answer;
		if (safeAnswer.length() > MAX_ANSWER_CONTEXT_LENGTH) {
			safeAnswer = safeAnswer.substring(0, MAX_ANSWER_CONTEXT_LENGTH);
		}
		return """
				请根据用户问题和已查询的业务回答，只选择一个最合适的图表类型并给出简短业务标题。
				如果用户明确指定了受支持的图表类型，必须优先选择该类型。
				只返回一个 JSON 对象，格式为 {"type":"bar","title":"简短业务标题"}。
				不得输出字段映射、业务数值、解释、多个 JSON 对象或其他内容。

				用户问题：
				%s

				业务回答：
				%s
				""".formatted(question, safeAnswer);
	}

	/**
	 * 从图表补选模型响应中解析唯一的两字段 JSON 对象。
	 *
	 * @param response 图表补选模型响应
	 * @return 合法图表选择，格式不合法时返回 null
	 */
	private ChartSelection parseSelection(ChatResponse response) {
		if (response == null || response.getResult() == null
				|| response.getResult().getOutput() == null) {
			return null;
		}
		String content = response.getResult().getOutput().getText();
		if (content == null || content.isBlank()
				|| content.getBytes(StandardCharsets.UTF_8).length > MAX_SELECTION_RESPONSE_BYTES) {
			return null;
		}
		String selectionJson = this.extractSingleJsonObject(content);
		if (selectionJson == null) {
			return null;
		}
		try {
			JSONObject selectionObject = JSON.parseObject(selectionJson);
			if (!selectionObject.keySet().equals(Set.of("type", "title"))) {
				return null;
			}
			return selectionObject.toJavaObject(ChartSelection.class);
		}
		catch (Exception ex) {
			return null;
		}
	}

	/**
	 * 提取响应中的唯一 JSON 对象，兼容对象外的 Markdown 代码围栏。
	 *
	 * @param content 模型原始响应文本
	 * @return 唯一 JSON 对象文本，存在额外正文或多个对象时返回 null
	 */
	private String extractSingleJsonObject(String content) {
		String normalized = content.strip();
		if (normalized.startsWith("```")) {
			int firstLineEnd = normalized.indexOf('\n');
			int closingFence = normalized.lastIndexOf("```");
			if (firstLineEnd < 0 || closingFence <= firstLineEnd
					|| !normalized.substring(closingFence + 3).isBlank()) {
				return null;
			}
			normalized = normalized.substring(firstLineEnd + 1, closingFence).strip();
		}
		if (!normalized.startsWith("{") || !normalized.endsWith("}")) {
			return null;
		}
		return normalized;
	}
}
