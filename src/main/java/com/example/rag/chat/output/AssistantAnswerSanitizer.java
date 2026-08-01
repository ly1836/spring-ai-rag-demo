package com.example.rag.chat.output;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * 助手最终答案净化器，隔离 Tool Calling 中间旁白与面向用户的业务回答。
 */
@Component
public class AssistantAnswerSanitizer {

	/** 模型最终答案边界标记，仅用于后端协议，不向前端返回。 */
	public static final String FINAL_ANSWER_MARKER = "<!--FINAL_ANSWER-->";

	/** 兼容旧消息中用于分隔内部过程和最终答案的 Markdown 水平线。 */
	private static final Pattern FINAL_SECTION_SEPARATOR = Pattern.compile("(?m)^\\s*---\\s*$");

	/** 可单独确认内部图表规划失败的协议标记。 */
	private static final String INTERNAL_PLAN_REJECTION_MARKER = "accepted=false";

	/** 可明确识别为内部执行步骤的段落开头。 */
	private static final List<String> INTERNAL_NARRATION_PREFIXES = List.of(
		"让我", "我需要先", "我先", "我尝试", "我将", "接下来我", "现在我", "不过，我");

	/** 流式阶段需要继续暂存判断的中文内部执行前缀。 */
	private static final List<String> STREAMING_INTERNAL_NARRATION_PREFIXES = List.of(
		"让我", "我需要先", "我先", "我尝试", "我将", "接下来我", "现在我", "不过，我");

	/** 流式阶段需要继续暂存判断的英文内部执行前缀。 */
	private static final List<String> STREAMING_ENGLISH_NARRATION_PREFIXES = List.of(
		"i need to", "i have to", "i'll", "i will", "let me",
		"first, i'll", "first i'll", "now, i'll", "now i'll");

	/** 可明确识别为英文内部执行步骤的前导句式。 */
	private static final Pattern LEADING_ENGLISH_INTERNAL_NARRATION = Pattern.compile(
		"^(?:I\\s+(?:need|have)\\s+to|I(?:'|’)ll|I\\s+will|Let\\s+me|"
			+ "First[,\\s]+I(?:'|’)ll|Now[,\\s]+I(?:'|’)ll)\\b",
		Pattern.CASE_INSENSITIVE);

	/** 英文内部执行旁白必须包含的查询或规划动作。 */
	private static final Pattern INTERNAL_ENGLISH_ACTION = Pattern.compile(
		"\\b(?:query|retrieve|fetch|search|check|plan|chart|visuali[sz]ation|tool|data)\\b",
		Pattern.CASE_INSENSITIVE);

	/** 英文前导句与中文最终答案之间的安全切分边界。 */
	private static final Pattern ENGLISH_TO_CHINESE_BOUNDARY = Pattern.compile(
		"(?<=[.!?])\\s*(?=[\\p{IsHan}0-9#|])");

	/** 用于确认英文前缀之后确实存在中文业务回答。 */
	private static final Pattern CHINESE_CONTENT = Pattern.compile("\\p{IsHan}");

	/**
	 * 净化完整模型输出，只保留面向用户的最终业务回答。
	 *
	 * @param content 模型原始输出
	 * @return 不含内部执行旁白和边界标记的回答
	 */
	public String sanitize(String content) {
		if (content == null || content.isBlank()) {
			return "";
		}
		int markerIndex = content.lastIndexOf(FINAL_ANSWER_MARKER);
		if (markerIndex >= 0) {
			return stripLeadingLineBreaks(
				content.substring(markerIndex + FINAL_ANSWER_MARKER.length())).stripTrailing();
		}
		return sanitizeLegacyContent(content);
	}

	/**
	 * 创建单次模型响应专用的流式净化会话。
	 *
	 * @return 新的流式净化会话
	 */
	public StreamSession openStream() {
		return new StreamSession(this);
	}

	/**
	 * 流式完成结果。
	 *
	 * @param content      需要持久化的完整最终回答
	 * @param pendingDelta 尚未发送给前端的兼容回答
	 */
	public record StreamCompletion(String content, String pendingDelta) {
	}

	/**
	 * 单次流式响应的有状态净化会话，允许最终答案标记跨网络分片。
	 */
	public static final class StreamSession {

		/** 当前使用的完整答案净化器。 */
		private final AssistantAnswerSanitizer sanitizer;

		/** 模型返回的全部原始文本，仅用于无标记时完成阶段兼容净化。 */
		private final StringBuilder rawContent = new StringBuilder();

		/** 发现最终答案标记前暂存的文本。 */
		private final StringBuilder pendingContent = new StringBuilder();

		/** 已确认需要向用户展示的最终文本。 */
		private final StringBuilder finalContent = new StringBuilder();

		/** 是否已经识别最终答案边界。 */
		private boolean answerStarted;

		/**
		 * 创建流式净化会话。
		 *
		 * @param sanitizer 完整答案净化器
		 */
		private StreamSession(AssistantAnswerSanitizer sanitizer) {
			this.sanitizer = sanitizer;
		}

		/**
		 * 接收模型文本增量，边界标记之前的内容不会向前端发送。
		 *
		 * @param chunk 模型文本增量
		 * @return 当前可安全发送的最终答案增量
		 */
		public String accept(String chunk) {
			if (chunk == null || chunk.isEmpty()) {
				return "";
			}
			rawContent.append(chunk);
			if (answerStarted) {
				finalContent.append(chunk);
				return chunk;
			}
			pendingContent.append(chunk);
			int markerIndex = pendingContent.indexOf(FINAL_ANSWER_MARKER);
			if (markerIndex < 0) {
				String safeDelta = sanitizer.resolveSafeStreamingDelta(pendingContent.toString());
				if (safeDelta == null) {
					return "";
				}
				// 无边界时一旦确认正文安全，立即切换为透传状态恢复实时输出。
				answerStarted = true;
				pendingContent.setLength(0);
				finalContent.append(safeDelta);
				return safeDelta;
			}
			answerStarted = true;
			String delta = stripLeadingLineBreaks(pendingContent.substring(
				markerIndex + FINAL_ANSWER_MARKER.length()));
			pendingContent.setLength(0);
			finalContent.append(delta);
			return delta;
		}

		/**
		 * 完成流式净化；模型未提供边界标记时一次性返回兼容净化结果。
		 *
		 * @return 完整最终回答和待补发增量
		 */
		public StreamCompletion finish() {
			if (answerStarted) {
				return new StreamCompletion(finalContent.toString().stripTrailing(), "");
			}
			// 终止时先删除末尾残缺协议标记，再按兼容规则净化剩余旁白。
			String completedContent = sanitizer.stripTrailingIncompleteFinalAnswerMarker(rawContent.toString());
			String sanitized = sanitizer.sanitize(completedContent);
			return new StreamCompletion(sanitized, sanitized);
		}

	}

	/**
	 * 兼容净化没有最终答案标记的旧模型输出和历史消息。
	 *
	 * @param content 原始完整文本
	 * @return 净化后的兼容文本
	 */
	private String sanitizeLegacyContent(String content) {
		String normalizedContent = this.stripLeadingEnglishInternalNarration(content);
		Matcher separatorMatcher = FINAL_SECTION_SEPARATOR.matcher(normalizedContent);
		int finalSectionStart = -1;
		while (separatorMatcher.find()) {
			finalSectionStart = separatorMatcher.end();
		}
		if (finalSectionStart >= 0) {
			String prefix = normalizedContent.substring(0, finalSectionStart);
			String finalSection = normalizedContent.substring(finalSectionStart).strip();
			if (!finalSection.isEmpty() && containsInternalNarration(prefix)) {
				return finalSection;
			}
		}

		// 旧消息没有明确边界时，仅删除能够确定为内部过程的完整段落。
		String[] paragraphs = normalizedContent.split("\\R\\s*\\R");
		List<String> retained = new ArrayList<>();
		for (String paragraph : paragraphs) {
			if (!isInternalNarrationParagraph(paragraph)) {
				retained.add(paragraph.strip());
			}
		}
		return String.join("\n\n", retained).strip();
	}

	/**
	 * 判断文本是否包含内部查询、规划或重试旁白。
	 *
	 * @param content 待检查文本
	 * @return 是否包含内部执行旁白
	 */
	private boolean containsInternalNarration(String content) {
		String normalized = content.stripLeading();
		return content.contains(INTERNAL_PLAN_REJECTION_MARKER)
			|| INTERNAL_NARRATION_PREFIXES.stream().anyMatch(normalized::startsWith)
			|| (LEADING_ENGLISH_INTERNAL_NARRATION.matcher(normalized).find()
				&& INTERNAL_ENGLISH_ACTION.matcher(normalized).find());
	}

	/**
	 * 判断完整段落是否属于内部执行过程。
	 *
	 * @param paragraph 待检查段落
	 * @return 是否应从用户回答中移除
	 */
	private boolean isInternalNarrationParagraph(String paragraph) {
		String normalized = paragraph.strip();
		return !normalized.isEmpty() && containsInternalNarration(normalized);
	}

	/**
	 * 删除最终答案边界之后无业务含义的前导换行。
	 *
	 * @param content 边界后的文本
	 * @return 去除前导换行的文本
	 */
	private static String stripLeadingLineBreaks(String content) {
		int start = 0;
		while (start < content.length()
				&& (content.charAt(start) == '\r' || content.charAt(start) == '\n')) {
			start++;
		}
		return content.substring(start);
	}

	/**
	 * 移除直接拼接在中文业务答案前的英文查询或图表规划旁白。
	 *
	 * @param content 没有最终答案边界的完整文本
	 * @return 移除明确英文内部前缀后的文本
	 */
	private String stripLeadingEnglishInternalNarration(String content) {
		String normalized = content.stripLeading();
		if (!LEADING_ENGLISH_INTERNAL_NARRATION.matcher(normalized).find()) {
			return content;
		}
		Matcher boundaryMatcher = ENGLISH_TO_CHINESE_BOUNDARY.matcher(normalized);
		if (!boundaryMatcher.find()) {
			return content;
		}
		String narrationPrefix = normalized.substring(0, boundaryMatcher.end());
		String finalContent = normalized.substring(boundaryMatcher.end()).stripLeading();
		if (!INTERNAL_ENGLISH_ACTION.matcher(narrationPrefix).find()) {
			return content;
		}
		if (!CHINESE_CONTENT.matcher(finalContent).find()) {
			return content;
		}
		return finalContent;
	}

	/**
	 * 从尚未出现最终答案边界的暂存文本中解析可安全发送的正文。
	 *
	 * @param content 当前暂存的模型文本
	 * @return 可立即发送的正文，仍可能是内部旁白时返回 null
	 */
	private String resolveSafeStreamingDelta(String content) {
		String normalized = content.stripLeading();
		if (normalized.isEmpty() || FINAL_ANSWER_MARKER.startsWith(normalized)
				|| this.trailingIncompleteFinalAnswerMarkerLength(normalized) > 0) {
			return null;
		}
		if (!this.isPotentialInternalStreamingPrefix(normalized)) {
			return normalized;
		}
		String sanitized = this.sanitizeLegacyContent(normalized);
		if (sanitized.isEmpty() || sanitized.equals(normalized)
				|| this.isPotentialInternalStreamingPrefix(sanitized)) {
			return null;
		}
		return sanitized;
	}

	/**
	 * 判断暂存文本是否仍可能是跨分片的中文或英文内部执行前缀。
	 *
	 * @param content 已去除前导空白的暂存文本
	 * @return 是否需要继续暂存判断
	 */
	private boolean isPotentialInternalStreamingPrefix(String content) {
		for (String prefix : STREAMING_INTERNAL_NARRATION_PREFIXES) {
			if (prefix.startsWith(content) || content.startsWith(prefix)) {
				return true;
			}
		}
		String lowerContent = content.toLowerCase(Locale.ROOT);
		for (String prefix : STREAMING_ENGLISH_NARRATION_PREFIXES) {
			if (prefix.startsWith(lowerContent) || lowerContent.startsWith(prefix)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 计算文本末尾匹配的最终答案标记非空真前缀长度。
	 *
	 * @param content 待检查的流式暂存文本
	 * @return 真前缀长度，不存在时返回 0
	 */
	private int trailingIncompleteFinalAnswerMarkerLength(String content) {
		int maxLength = Math.min(content.length(), FINAL_ANSWER_MARKER.length() - 1);
		for (int length = maxLength; length > 0; length--) {
			if (content.endsWith(FINAL_ANSWER_MARKER.substring(0, length))) {
				return length;
			}
		}
		return 0;
	}

	/**
	 * 删除文本末尾尚未接收完整的最终答案标记。
	 *
	 * @param content 模型原始流式文本
	 * @return 不含末尾残缺协议标记的文本
	 */
	private String stripTrailingIncompleteFinalAnswerMarker(String content) {
		int markerPrefixLength = this.trailingIncompleteFinalAnswerMarkerLength(content);
		return markerPrefixLength == 0 ? content : content.substring(0, content.length() - markerPrefixLength);
	}

}
