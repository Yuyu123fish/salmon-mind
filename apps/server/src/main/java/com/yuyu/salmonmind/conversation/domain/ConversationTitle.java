package com.yuyu.salmonmind.conversation.domain;

/**
 * Conversation 标题规则：新建时为临时标题，首条用户 Entry 确认后替换为单行截断文本。
 */
public class ConversationTitle {

    public static final String DEFAULT_TITLE = "新对话";

    // 与 conversations.title VARCHAR(120) 的列宽对齐；截断发生在字符边界，不额外调用模型
    private static final int MAX_TITLE_CHARS = 120;

    public static String fromFirstUserEntry(String text) {
        String singleLine = text.replaceAll("\\s+", " ").trim();
        return singleLine.length() <= MAX_TITLE_CHARS ? singleLine : singleLine.substring(0, MAX_TITLE_CHARS);
    }

    private ConversationTitle() {
    }
}
