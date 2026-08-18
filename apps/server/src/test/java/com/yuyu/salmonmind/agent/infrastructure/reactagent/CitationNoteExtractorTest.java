package com.yuyu.salmonmind.agent.infrastructure.reactagent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Citation Note 只从引用附近 Agent 文本提取并保持有界的单元测试。 */
class CitationNoteExtractorTest {

    @Test
    void extractsNearbyAgentSentenceWithoutCitationMarkersOrMarkdownDecoration() {
        String answer = "结论是 **当前版本** 支持本地检索 [L1]，但网页摘要 [W1] 只能作为补充。";
        int start = answer.indexOf("[L1]");

        String note = CitationNoteExtractor.extract(answer, start, start + 4);

        assertThat(note).contains("当前版本").doesNotContain("[L1]", "[W1]", "**");
    }

    @Test
    void returnsNullForAnIsolatedCitationAndLimitsUnicodeText() {
        String isolated = "[L1]";
        assertThat(CitationNoteExtractor.extract(isolated, 0, isolated.length())).isNull();

        String answer = "说明 " + "鱼".repeat(400) + " [L1]。";
        int start = answer.indexOf("[L1]");
        String note = CitationNoteExtractor.extract(answer, start, start + 4);

        assertThat(note).isNotNull();
        assertThat(note.length()).isLessThanOrEqualTo(CitationNoteExtractor.MAX_CHARS);
    }
}
