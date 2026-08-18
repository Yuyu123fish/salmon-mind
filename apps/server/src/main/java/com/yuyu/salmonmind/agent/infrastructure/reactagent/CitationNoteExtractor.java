package com.yuyu.salmonmind.agent.infrastructure.reactagent;

import java.util.regex.Pattern;

/**
 * 从最终 Agent 文本中提取引用首次出现处的附近陈述。
 *
 * <p>它只读取回答正文，不读取 Tool Result，因此生成的 Note 始终是 Agent 自己写出的
 * 相关性说明；找不到有意义陈述时返回 null，不用来源摘录冒充 Agent 总结。
 */
final class CitationNoteExtractor {

    static final int MAX_CHARS = 320;
    private static final Pattern REFERENCE = Pattern.compile(
            "(?<![A-Za-z0-9_])\\[(?:L|W)[1-9][0-9]*](?![A-Za-z0-9_])");
    private static final Pattern MARKDOWN_DECORATION = Pattern.compile(
            "(?:\\*\\*|__|~~|`|^\\s{0,3}[>#*-+]\\s+)");
    private static final Pattern LINK = Pattern.compile("!?\\[([^]]+)]\\([^)]*\\)");

    private CitationNoteExtractor() {
    }

    static String extract(String answer, int markerStart, int markerEnd) {
        if (answer == null || answer.isBlank() || markerStart < 0 || markerEnd > answer.length()
                || markerStart >= markerEnd) {
            return null;
        }
        int start = segmentStart(answer, markerStart);
        int end = segmentEnd(answer, markerEnd);
        String note = normalize(answer.substring(start, end));
        if (note.isBlank() || !hasMeaningfulContent(note)) {
            return null;
        }
        return limit(note, MAX_CHARS);
    }

    private static int segmentStart(String text, int markerStart) {
        int start = Math.max(text.lastIndexOf('\n', markerStart - 1), text.lastIndexOf('\r', markerStart - 1)) + 1;
        for (int i = markerStart - 1; i >= start; i--) {
            if (isSentenceEnd(text.charAt(i))) {
                return i + 1;
            }
        }
        return start;
    }

    private static int segmentEnd(String text, int markerEnd) {
        for (int i = markerEnd; i < text.length(); i++) {
            char current = text.charAt(i);
            if (current == '\n' || current == '\r') {
                return i;
            }
            if (isSentenceEnd(current)) {
                return i + 1;
            }
        }
        return text.length();
    }

    private static boolean isSentenceEnd(char value) {
        return value == '。' || value == '！' || value == '？'
                || value == '!' || value == '?' || value == '.';
    }

    private static String normalize(String value) {
        String withoutReferences = REFERENCE.matcher(value).replaceAll("");
        String withoutLinks = LINK.matcher(withoutReferences).replaceAll("$1");
        String withoutDecoration = MARKDOWN_DECORATION.matcher(withoutLinks).replaceAll("");
        return withoutDecoration.replaceAll("\\p{Cc}", " ")
                .replaceAll("\\s+", " ").trim();
    }

    private static boolean hasMeaningfulContent(String value) {
        return value.codePoints().anyMatch(codePoint ->
                Character.isLetterOrDigit(codePoint) || Character.getType(codePoint) == Character.OTHER_LETTER);
    }

    static String limit(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        int end = maxChars;
        if (end > 0 && end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }
}
