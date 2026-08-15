package com.yuyu.salmonmind.conversation.domain;

/**
 * Conversation 标题规则：新建时为临时默认标题，此后标题只来自模型生成的 Title Entry。
 * Stage 2 起不再使用「首条用户消息截断」作为产品标题。
 */
public class ConversationTitle {

    public static final String DEFAULT_TITLE = "新对话";

    // 与 conversations.title VARCHAR(120) 的列宽对齐；截断发生在字符边界
    private static final int MAX_TITLE_CHARS = 120;

    /**
     * 模型生成标题的规范化：去除首尾空白、折叠为单行并按既有最大长度截断。
     * 空白或格式非法的结果由调用方在写入 Title Entry 前拒绝，本方法不负责判定。
     */
    public static String normalize(String title) {
        String singleLine = title.replaceAll("\\s+", " ").trim();
        return singleLine.length() <= MAX_TITLE_CHARS ? singleLine : singleLine.substring(0, MAX_TITLE_CHARS);
    }

    private ConversationTitle() {
    }
}
