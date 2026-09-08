package com.example.rag.chat.rag;

import java.util.ArrayList;
import java.util.List;

/**
 * 从 Markdown 正文提取真实引用编号，忽略代码和转义字面量。
 */
public final class RagCitationNumberExtractor {

	/**
	 * 禁止实例化纯引用解析工具。
	 */
	private RagCitationNumberExtractor() {
	}

	/**
	 * 按正文出现顺序提取引用编号文本。
	 *
	 * @param answer Markdown 回答
	 * @return 不含代码和转义字面量的引用编号
	 */
	public static List<String> extract(String answer) {
		if (answer == null || answer.isEmpty()) {
			return List.of();
		}
		List<String> citations = new ArrayList<>();
		char fenceCharacter = 0;
		int fenceLength = 0;
		int inlineCodeLength = 0;
		boolean lineStart = true;
		int index = 0;
		while (index < answer.length()) {
			// 围栏仅在行首最多三个空格后生效，避免把正文中的波浪线误作代码块。
			FenceMarker marker = lineStart ? readFenceMarker(answer, index) : null;
			if (marker != null && (fenceCharacter == 0
					|| marker.character() == fenceCharacter && marker.length() >= fenceLength
						&& isClosingFence(answer, marker.endIndex()))) {
				if (fenceCharacter == 0) {
					fenceCharacter = marker.character();
					fenceLength = marker.length();
				}
				else {
					fenceCharacter = 0;
					fenceLength = 0;
				}
				index = marker.endIndex();
				lineStart = false;
				continue;
			}
			char current = answer.charAt(index);
			if (current == '\n') {
				lineStart = true;
				index++;
				continue;
			}
			if (fenceCharacter != 0) {
				// 围栏正文只在真正的行首检查关闭标记，行内围栏字符按字面量处理。
				lineStart = false;
				index++;
				continue;
			}
			lineStart = false;
			if (current == '`') {
				int runLength = countRun(answer, index, '`');
				// 仅在存在配对结束标记时进入行内代码，未闭合标记按普通文本处理。
				if (inlineCodeLength == 0
						&& hasClosingInlineCodeMarker(answer, index + runLength, runLength)) {
					inlineCodeLength = runLength;
				}
				else if (inlineCodeLength == runLength) {
					inlineCodeLength = 0;
				}
				index += runLength;
				continue;
			}
			if (inlineCodeLength != 0 || current != '[' || isEscaped(answer, index)) {
				index++;
				continue;
			}
			int digitStart = index + 1;
			int digitEnd = digitStart;
			while (digitEnd < answer.length() && Character.isDigit(answer.charAt(digitEnd))) {
				digitEnd++;
			}
			if (digitEnd > digitStart && digitEnd < answer.length()
					&& answer.charAt(digitEnd) == ']') {
				citations.add(answer.substring(digitStart, digitEnd));
				index = digitEnd + 1;
				continue;
			}
			index++;
		}
		return List.copyOf(citations);
	}

	/**
	 * 读取行首 Markdown 围栏标记。
	 *
	 * @param text  Markdown 文本
	 * @param index 当前行首位置
	 * @return 合法围栏标记或 null
	 */
	private static FenceMarker readFenceMarker(String text, int index) {
		int markerStart = index;
		while (markerStart < text.length() && markerStart - index < 3
				&& text.charAt(markerStart) == ' ') {
			markerStart++;
		}
		if (markerStart >= text.length()) {
			return null;
		}
		char character = text.charAt(markerStart);
		if (character != '`' && character != '~') {
			return null;
		}
		int length = countRun(text, markerStart, character);
		return length < 3 ? null : new FenceMarker(character, length, markerStart + length);
	}

	/**
	 * 统计当前位置开始的相同字符数量。
	 *
	 * @param text      待检查文本
	 * @param index     起始位置
	 * @param character 目标字符
	 * @return 连续字符数量
	 */
	private static int countRun(String text, int index, char character) {
		int end = index;
		while (end < text.length() && text.charAt(end) == character) {
			end++;
		}
		return end - index;
	}

	/**
	 * 判断当前左方括号是否被奇数个反斜杠转义。
	 *
	 * @param text  Markdown 文本
	 * @param index 左方括号位置
	 * @return 是否为转义字面量
	 */
	private static boolean isEscaped(String text, int index) {
		int slashCount = 0;
		for (int current = index - 1; current >= 0 && text.charAt(current) == '\\'; current--) {
			slashCount++;
		}
		return slashCount % 2 == 1;
	}

	/**
	 * Markdown 围栏字符、长度和标记结束位置。
	 *
	 * @param character 围栏字符
	 * @param length 围栏长度
	 * @param endIndex 标记结束位置
	 */
	private record FenceMarker(char character, int length, int endIndex) {
	}

	/**
	 * 判断围栏标记后是否只剩行尾空白。
	 *
	 * @param text  Markdown 文本
	 * @param index 围栏标记后的起始位置
	 * @return 是否为合法关闭围栏
	 */
	private static boolean isClosingFence(String text, int index) {
		int current = index;
		while (current < text.length()
				&& (text.charAt(current) == ' ' || text.charAt(current) == '\t')) {
			current++;
		}
		return current >= text.length() || text.charAt(current) == '\n' || text.charAt(current) == '\r';
	}

	/**
	 * 判断后续文本是否存在相同长度的行内代码结束标记。
	 *
	 * @param text         Markdown 文本
	 * @param index        开始查找的位置
	 * @param markerLength 开始标记长度
	 * @return 是否存在配对结束标记
	 */
	private static boolean hasClosingInlineCodeMarker(String text, int index, int markerLength) {
		char fenceCharacter = 0;
		int fenceLength = 0;
		boolean lineStart = index == 0 || text.charAt(index - 1) == '\n';
		int current = index;
		while (current < text.length()) {
			// 配对查找复用主解析的围栏规则，不能使用代码块内的反引号。
			FenceMarker fenceMarker = lineStart ? readFenceMarker(text, current) : null;
			if (fenceMarker != null && (fenceCharacter == 0
					|| fenceMarker.character() == fenceCharacter && fenceMarker.length() >= fenceLength
						&& isClosingFence(text, fenceMarker.endIndex()))) {
				if (fenceCharacter == 0) {
					fenceCharacter = fenceMarker.character();
					fenceLength = fenceMarker.length();
				}
				else {
					fenceCharacter = 0;
					fenceLength = 0;
				}
				current = fenceMarker.endIndex();
				lineStart = false;
				continue;
			}
			char character = text.charAt(current);
			if (character == '\n') {
				lineStart = true;
				current++;
				continue;
			}
			if (fenceCharacter != 0) {
				lineStart = false;
				current++;
				continue;
			}
			lineStart = false;
			if (character == '`') {
				int candidateLength = countRun(text, current, '`');
				if (candidateLength == markerLength) {
					return true;
				}
				current += candidateLength;
				continue;
			}
			current++;
		}
		return false;
	}

}
