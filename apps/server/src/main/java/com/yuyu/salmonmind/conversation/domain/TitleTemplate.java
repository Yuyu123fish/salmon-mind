package com.yuyu.salmonmind.conversation.domain;

/**
 * Conversation 首次标题生成的固定 Prompt 合同。标题是 Conversation 级元数据事件，
 * 使用当前 Chat 模型独立、非流式轻量调用，输入只包含首次成功交互的 User 与 Assistant 内容，
 * 不复用或推进 ReactAgent Checkpoint。
 */
public final class TitleTemplate {

    private static final String PROMPT = """
            为下面的首次对话生成一个简洁的对话标题。
            要求：只输出一行标题，不允许解释、引号、Markdown 或换行。

            用户：%s
            助手：%s
            """;

    private TitleTemplate() {
    }

    public static String render(String userText, String assistantText) {
        return String.format(PROMPT, userText, assistantText);
    }
}
