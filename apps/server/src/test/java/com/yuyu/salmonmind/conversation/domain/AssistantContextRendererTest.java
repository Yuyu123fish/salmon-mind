package com.yuyu.salmonmind.conversation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuyu.salmonmind.conversation.api.AssistantMessagePayload;
import com.yuyu.salmonmind.conversation.api.AssistantCompletionStatus;
import com.yuyu.salmonmind.conversation.api.CitationPayload;
import com.yuyu.salmonmind.conversation.api.LocalCitationPayload;
import com.yuyu.salmonmind.conversation.api.LocalRetrievedSourcePayload;
import com.yuyu.salmonmind.conversation.api.RunTraceItemPayload;
import com.yuyu.salmonmind.conversation.api.ToolOutcomeDetailPayload;
import com.yuyu.salmonmind.conversation.api.ToolRequestDetailPayload;
import com.yuyu.salmonmind.conversation.api.WebCitationPayload;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Citation 历史投影的格式、作用域和非可信字段边界测试。 */
class AssistantContextRendererTest {

    @Test
    void keepsOldAssistantTextSemanticsAndNeverProjectsDisplayTrace() {
        AssistantMessagePayload payload = new AssistantMessagePayload(
                "普通回答", UUID.randomUUID(), "provider", "model", null, List.of(),
                List.of(
                        RunTraceItemPayload.reasoning("不可进入模型的推理", false),
                        RunTraceItemPayload.tool(
                                "call-1", "search", RunTraceItemPayload.ToolStatus.COMPLETED,
                                "不可进入模型的工具摘要", null,
                                new ToolRequestDetailPayload("不可进入模型的查询", false, "week", false, 3, false),
                                new ToolOutcomeDetailPayload("BOCHA", ToolOutcomeDetailPayload.ResultStatus.SUCCESS,
                                        "COMPLETE", 2, 12, false, true), false)));

        assertThat(AssistantContextRenderer.render(payload)).isEqualTo("普通回答")
                .doesNotContain("不可进入模型的查询", "BOCHA", "COMPLETE", "resultTruncated");
    }

    @Test
    void ignoresCitationNotesAndRetrievedSourcePreviewsInFutureModelContext() {
        AssistantMessagePayload payload = new AssistantMessagePayload(
                "回答 [L1]", UUID.randomUUID(), "provider", "model", null,
                List.of(new LocalCitationPayload("L1", UUID.randomUUID(), UUID.randomUUID(),
                        "manual.md", "p1", "Agent 不应被重复注入的摘要")),
                List.of(new LocalRetrievedSourcePayload(
                        "L1", UUID.randomUUID(), UUID.randomUUID(), "manual.md", "p1",
                        Instant.parse("2026-08-17T00:00:00Z"), "LOCAL_EVIDENCE",
                        "Agent 不应看到的来源摘录")),
                List.of());

        String rendered = AssistantContextRenderer.render(payload);

        assertThat(rendered).contains("回答 [L1]")
                .doesNotContain("Agent 不应被重复注入的摘要", "Agent 不应看到的来源摘录");
    }

    @Test
    void rendersBoundedRunScopedMetadataWithoutLeakingLocalPath() {
        UUID runId = UUID.randomUUID();
        List<CitationPayload> citations = List.of(
                new LocalCitationPayload("L1", UUID.randomUUID(), UUID.randomUUID(),
                        "C:\\private\\manual.md", "objects/secret/page-2"),
                new WebCitationPayload("W1", "BOCHA", "官方页面", "https://example.com/a",
                        "example.com", "2026-08-17", Instant.parse("2026-08-17T00:00:00Z")));
        AssistantMessagePayload payload = new AssistantMessagePayload(
                "结论 [L1] [W1]", runId, "provider", "model", null, citations);

        String rendered = AssistantContextRenderer.render(payload);

        assertThat(rendered).contains("结论 [L1] [W1]")
                .contains("runId: " + runId)
                .contains("source=LOCAL document=manual.md")
                .contains("location=位置已隐藏")
                .contains("source=WEB provider=BOCHA title=官方页面")
                .contains("url=https://example.com/a")
                .contains("不是当前 Run 可引用证据")
                .contains("如需核验必须重新检索");
        assertThat(rendered).doesNotContain("C:\\private").doesNotContain("objects/secret");
        assertThat(rendered.length()).isLessThanOrEqualTo(4_200);
    }

    @Test
    void marksLengthIncompleteAssistantInFutureModelContext() {
        AssistantMessagePayload payload = new AssistantMessagePayload(
                "截断的正文", UUID.randomUUID(), "provider", "model", null,
                List.of(), List.of(), List.of(),
                AssistantCompletionStatus.INCOMPLETE_LENGTH, null);

        assertThat(AssistantContextRenderer.render(payload))
                .contains("截断的正文")
                .contains("回答未完成")
                .contains("继续生成需由用户触发");
    }
}
