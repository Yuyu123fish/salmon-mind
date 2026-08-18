package com.yuyu.salmonmind.agent.infrastructure.reactagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** Tool Request Detail 白名单、归一化与失败退化测试。 */
class ToolRequestDetailProjectorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void projectsOnlyAllowedLocalQueryAndDropsExtraFields() {
        var detail = ToolRequestDetailProjector.project(
                "search_local_knowledge",
                "{\"query\":\" salmon \",\"secret\":\"canary-secret\",\"limit\":99}",
                mapper);

        assertThat(detail).isNotNull();
        assertThat(detail.querySummary()).isEqualTo("salmon");
        assertThat(detail.freshness()).isNull();
        assertThat(detail.count()).isNull();
        assertThat(detail.toString()).doesNotContain("canary-secret", "limit");
    }

    @Test
    void projectsWebDefaultsAndExplicitOptionsSeparately() {
        var defaults = ToolRequestDetailProjector.project(
                "search_web_bocha", "{\"query\":\"最新版本\"}", mapper);
        var explicit = ToolRequestDetailProjector.project(
                "search_web_searchapi",
                "{\"query\":\"最新版本\",\"freshness\":\"week\",\"count\":3}",
                mapper);

        assertThat(defaults).isNotNull().satisfies(detail -> {
            assertThat(detail.freshness()).isEqualTo("any");
            assertThat(detail.freshnessDefaulted()).isTrue();
            assertThat(detail.count()).isEqualTo(5);
            assertThat(detail.countDefaulted()).isTrue();
        });
        assertThat(explicit).isNotNull().satisfies(detail -> {
            assertThat(detail.freshness()).isEqualTo("week");
            assertThat(detail.freshnessDefaulted()).isFalse();
            assertThat(detail.count()).isEqualTo(3);
            assertThat(detail.countDefaulted()).isFalse();
        });
    }

    @Test
    void rejectsUnknownToolsInvalidArgumentsAndOutOfRangeValues() {
        assertThat(ToolRequestDetailProjector.project(
                "future_tool", "{\"query\":\"secret\"}", mapper)).isNull();
        assertThat(ToolRequestDetailProjector.project(
                "search_web_bocha", "[\"query\"]", mapper)).isNull();
        assertThat(ToolRequestDetailProjector.project(
                "search_web_bocha", "{\"query\":\"q\"}{\"query\":\"canary\"}", mapper)).isNull();
        assertThat(ToolRequestDetailProjector.project(
                "search_web_bocha", "{\"query\":\"q\",\"count\":11}", mapper)).isNull();
        assertThat(ToolRequestDetailProjector.project(
                "search_local_knowledge",
                mapper.createObjectNode().put("query", "\t\u0000  ").toString(), mapper)).isNull();
    }

    @Test
    void normalizesControlsAndTruncatesAtUnicodeBoundary() {
        var normalized = ToolRequestDetailProjector.project(
                "search_local_knowledge", "{\"query\":\"  A\\tB\\nC  \"}", mapper);
        var emoji = "🙂".repeat(513);
        var bounded = ToolRequestDetailProjector.project(
                "search_local_knowledge", mapper.createObjectNode().put("query", emoji).toString(), mapper);

        assertThat(normalized).isNotNull();
        assertThat(normalized.querySummary()).isEqualTo("A B C");
        assertThat(bounded).isNotNull();
        assertThat(bounded.querySummaryTruncated()).isTrue();
        assertThat(bounded.querySummary().codePointCount(0, bounded.querySummary().length())).isEqualTo(512);
        assertThat(bounded.querySummary()).doesNotEndWith("?");
    }
}
