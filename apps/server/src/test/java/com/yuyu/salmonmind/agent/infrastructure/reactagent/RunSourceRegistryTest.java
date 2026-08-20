package com.yuyu.salmonmind.agent.infrastructure.reactagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.salmonmind.agent.api.AgentCitation;
import com.yuyu.salmonmind.agent.api.AgentWebRetrievedSource;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Run-local L/W 身份唯一性、正文核对和结构化边界测试。 */
class RunSourceRegistryTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void sharesWebSequenceAndPersistsOnlyExactReferencedSources() throws Exception {
        RunSourceRegistry registry = new RunSourceRegistry(mapper);
        String local = envelope("LOCAL", "LOCAL", """
                {"evidenceId":"%s","revisionId":"%s","documentName":"manual.md","location":"p1","text":"local"}
                """.formatted(UUID.randomUUID(), UUID.randomUUID()));
        String bocha = webEnvelope("BOCHA", "https://example.com/a", "博查结果");
        String searchApi = webEnvelope("SEARCH_API", "https://example.org/b", "SearchApi 结果");

        RunSourceRegistry.Decoration localDecoration = registry.decorate(local, 10_000);
        RunSourceRegistry.Decoration bochaDecoration = registry.decorate(bocha, 10_000);
        RunSourceRegistry.Decoration searchApiDecoration = registry.decorate(searchApi, 10_000);

        assertThat(mapper.readTree(localDecoration.result()).path("items").get(0).path("referenceId").asText())
                .isEqualTo("L1");
        assertThat(mapper.readTree(bochaDecoration.result()).path("items").get(0).path("referenceId").asText())
                .isEqualTo("W1");
        assertThat(mapper.readTree(searchApiDecoration.result()).path("items").get(0).path("referenceId").asText())
                .isEqualTo("W2");

        var citations = registry.citationsFor("依据 [W2]、[L1]、[W2] 和伪造 [W99]");
        assertThat(citations).extracting("referenceId").containsExactly("W2", "L1");
        assertThat(citations.get(0).citationNote()).contains("依据");
        assertThat(registry.retrievedSources()).hasSize(3)
                .extracting("referenceId").containsExactly("L1", "W1", "W2");
    }

    @Test
    void removesWholeItemsWhenBoundIsSmallAndKeepsJsonValid() throws Exception {
        RunSourceRegistry registry = new RunSourceRegistry(mapper);
        String result = webEnvelope("BOCHA", "https://example.com/a", "x".repeat(2_000));

        RunSourceRegistry.Decoration decoration = registry.decorate(result, 300);

        JsonNode bounded = mapper.readTree(decoration.result());
        assertThat(bounded.isObject()).isTrue();
        assertThat(bounded.path("truncated").asBoolean()).isTrue();
        assertThat(bounded.path("items").isArray()).isTrue();
    }

    @Test
    void keepsCitationWhenTrimmedDuplicateSharesTheSurvivingReference() throws Exception {
        String single = webEnvelope("BOCHA", "https://example.com/a", "first");
        int oneItemLimit = new RunSourceRegistry(mapper).decorate(single, 10_000).result().length() + 5;
        RunSourceRegistry registry = new RunSourceRegistry(mapper);

        RunSourceRegistry.Decoration decoration = registry.decorate(duplicateWebEnvelope(), oneItemLimit);

        assertThat(decoration.truncated()).isTrue();
        assertThat(mapper.readTree(decoration.result()).path("items")).singleElement()
                .extracting(node -> node.path("referenceId").asText())
                .isEqualTo("W1");
        assertThat(registry.citationsFor("依据 [W1]")).hasSize(1);
    }

    @Test
    void characterBoundRemovesWholeSourceItemsAndOnlySurvivorsCanBeCited() throws Exception {
        String single = webEnvelope("BOCHA", "https://example.com/a", "first");
        RunSourceRegistry oneItemRegistry = new RunSourceRegistry(mapper);
        int oneItemChars = oneItemRegistry.decorate(single, 10_000).result().length();
        RunSourceRegistry registry = new RunSourceRegistry(mapper);

        RunSourceRegistry.Decoration decoration = registry.decorate(
                duplicateWebEnvelope(), oneItemChars + 5);
        JsonNode bounded = mapper.readTree(decoration.result());

        assertThat(bounded.path("items").isArray()).isTrue();
        assertThat(bounded.path("items")).hasSize(1);
        assertThat(registry.citationsFor("依据 [W1] [W2]")).extracting(AgentCitation::referenceId)
                .containsExactly("W1");
    }

    @Test
    void boundsRetrievedSourcesToThirtyTwoAndKeepsExcerptKindsSeparate() throws Exception {
        RunSourceRegistry registry = new RunSourceRegistry(mapper);
        StringBuilder items = new StringBuilder();
        for (int i = 1; i <= 33; i++) {
            if (i > 1) {
                items.append(',');
            }
            items.append("{\"title\":\"结果").append(i)
                    .append("\",\"url\":\"https://example.com/").append(i)
                    .append("\",\"site\":\"example.com\",\"snippet\":\"网页摘要\",")
                    .append("\"retrievedAt\":\"2026-08-17T00:00:00Z\"}");
        }
        String result = "{\"status\":\"SUCCESS\",\"reason\":\"NONE\",\"sourceKind\":\"WEB\","
                + "\"provider\":\"BOCHA\",\"items\":[" + items + "]}";

        RunSourceRegistry.Decoration decoration = registry.decorate(result, 200_000);

        assertThat(decoration.sourceCount()).isEqualTo(32);
        assertThat(registry.retrievedSources()).hasSize(32)
                .allSatisfy(source -> assertThat(source.excerptKind()).isEqualTo("WEB_SEARCH_SUMMARY"));
        assertThat(registry.citationsFor("依据 [W33]")).isEmpty();
    }

    @Test
    void freezesFirstOriginAndFinalPositionAfterDuplicateAndTailTrim() throws Exception {
        RunSourceRegistry registry = new RunSourceRegistry(mapper);
        String firstCall = twoWebEnvelope();
        RunSourceRegistry.Decoration first = registry.decorate(firstCall, 10_000, "call-1");
        assertThat(first.sourceCount()).isEqualTo(2);
        assertThat(registry.retrievedSources())
                .extracting("originToolCallId", "resultPosition")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("call-1", 1),
                        org.assertj.core.groups.Tuple.tuple("call-1", 2));
        assertThat(registry.retrievedSources()).element(0)
                .isInstanceOf(AgentWebRetrievedSource.class)
                .extracting("providerRank").isEqualTo(4);

        String duplicateAndNew = "{\"status\":\"SUCCESS\",\"reason\":\"NONE\",\"sourceKind\":\"WEB\","
                + "\"provider\":\"BOCHA\",\"items\":["
                + "{\"title\":\"again\",\"url\":\"https://example.com/a\",\"site\":\"example\","
                + "\"snippet\":\"snippet\",\"retrievedAt\":\"2026-08-17T00:00:00Z\",\"providerRank\":99},"
                + "{\"title\":\"new\",\"url\":\"https://example.com/c\",\"site\":\"example\","
                + "\"snippet\":\"snippet\",\"retrievedAt\":\"2026-08-17T00:00:00Z\",\"providerRank\":3}]}";
        registry.decorate(duplicateAndNew, 10_000, "call-2");

        assertThat(registry.retrievedSources()).extracting("referenceId", "originToolCallId", "resultPosition")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("W1", "call-1", 1),
                        org.assertj.core.groups.Tuple.tuple("W2", "call-1", 2),
                        org.assertj.core.groups.Tuple.tuple("W3", "call-2", 2));

        int oneItemLimit = new RunSourceRegistry(mapper)
                .decorate(webEnvelope("BOCHA", "https://example.com/z", "z"), 10_000).result().length() + 5;
        RunSourceRegistry trimmedRegistry = new RunSourceRegistry(mapper);
        RunSourceRegistry.Decoration trimmed = trimmedRegistry.decorate(
                duplicateWebEnvelope(), oneItemLimit, "call-trim");
        assertThat(trimmed.truncated()).isTrue();
        assertThat(trimmedRegistry.retrievedSources()).extracting("referenceId", "resultPosition")
                .containsExactly(org.assertj.core.groups.Tuple.tuple("W1", 1));
    }

    @Test
    void rollsBackNewSourcesWhenBoundResultIsRejected() {
        RunSourceRegistry registry = new RunSourceRegistry(mapper);
        RunSourceRegistry.Decoration decoration = registry.decorate(
                webEnvelope("BOCHA", "https://example.com/rollback", "rollback"),
                10_000, "call-rollback");

        registry.rollback(decoration);

        assertThat(registry.retrievedSources()).isEmpty();
        assertThat(registry.citationsFor("依据 [W1]")).isEmpty();
    }

    @Test
    void keepsCodebaseResultsOutOfSourceRegistryAndTrimsWholeItems() throws Exception {
        RunSourceRegistry registry = new RunSourceRegistry(mapper);
        String longText = "x".repeat(180);
        String result = "{\"status\":\"SUCCESS\",\"reason\":\"COMPLETE\",\"sourceKind\":\"CODEBASE\","
                + "\"provider\":\"CODEBASE\",\"operation\":\"read_repository_file\",\"items\":["
                + "{\"path\":\"src/Main.java\",\"line\":1,\"text\":\"" + longText + "\"},"
                + "{\"path\":\"src/Main.java\",\"line\":2,\"text\":\"" + longText + "\"}]}";

        RunSourceRegistry.Decoration decoration = registry.decorate(result, 500, "codebase-call");
        JsonNode bounded = mapper.readTree(decoration.result());

        assertThat(decoration.sourceCount()).isNull();
        assertThat(decoration.truncated()).isTrue();
        assertThat(bounded.path("sourceKind").asText()).isEqualTo("CODEBASE");
        assertThat(bounded.path("items")).hasSize(1);
        assertThat(bounded.path("startLine").asInt()).isEqualTo(1);
        assertThat(bounded.path("endLine").asInt()).isEqualTo(1);
        assertThat(bounded.path("continuation").asText()).isEqualTo("src/Main.java:2");
        assertThat(bounded.path("items").get(0).has("referenceId")).isFalse();
        assertThat(registry.retrievedSources()).isEmpty();
        assertThat(registry.citationsFor("依据 [L1] [W1]")).isEmpty();
    }

    private String envelope(String kind, String provider, String item) {
        return "{\"status\":\"SUCCESS\",\"reason\":\"NONE\",\"sourceKind\":\""
                + kind + "\",\"provider\":\"" + provider + "\",\"items\":[" + item + "]}";
    }

    private String webEnvelope(String provider, String url, String title) {
        return "{\"status\":\"SUCCESS\",\"reason\":\"NONE\",\"sourceKind\":\"WEB\","
                + "\"provider\":\"" + provider + "\",\"items\":[{"
                + "\"title\":\"" + title + "\",\"url\":\"" + url + "\","
                + "\"site\":\"example\",\"snippet\":\"snippet\","
                + "\"retrievedAt\":\"" + Instant.parse("2026-08-17T00:00:00Z") + "\"}]}";
    }

    private String twoWebEnvelope() {
        return """
                {"status":"SUCCESS","reason":"NONE","sourceKind":"WEB","provider":"BOCHA","items":[
                  {"title":"a","url":"https://example.com/a","site":"example","snippet":"snippet","retrievedAt":"2026-08-17T00:00:00Z","providerRank":4},
                  {"title":"b","url":"https://example.com/b","site":"example","snippet":"snippet","retrievedAt":"2026-08-17T00:00:00Z","providerRank":7}
                ]}
                """;
    }

    private String duplicateWebEnvelope() {
        return "{\"status\":\"SUCCESS\",\"reason\":\"NONE\",\"sourceKind\":\"WEB\","
                + "\"provider\":\"BOCHA\",\"items\":["
                + "{\"title\":\"first\",\"url\":\"https://example.com/a\","
                + "\"site\":\"example\",\"snippet\":\"snippet\","
                + "\"retrievedAt\":\"2026-08-17T00:00:00Z\"},"
                + "{\"title\":\"second\",\"url\":\"https://example.com/a\","
                + "\"site\":\"example\",\"snippet\":\"snippet\","
                + "\"retrievedAt\":\"2026-08-17T00:00:00Z\"}]}";
    }
}
