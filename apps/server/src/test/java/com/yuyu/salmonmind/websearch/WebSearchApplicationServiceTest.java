package com.yuyu.salmonmind.websearch.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuyu.salmonmind.websearch.api.WebSearchService;
import com.yuyu.salmonmind.websearch.api.WebSearchService.WebSearchFreshness;
import com.yuyu.salmonmind.websearch.api.WebSearchService.WebSearchProvider;
import com.yuyu.salmonmind.websearch.api.WebSearchService.WebSearchReason;
import com.yuyu.salmonmind.websearch.api.WebSearchService.WebSearchRequest;
import com.yuyu.salmonmind.websearch.api.WebSearchService.WebSearchStatus;
import com.yuyu.salmonmind.websearch.application.port.WebSearchProviderException;
import com.yuyu.salmonmind.websearch.application.port.WebSearchProviderPort;

import org.junit.jupiter.api.Test;

import java.util.List;

/** WebSearch 公共合同的归一化、去重和稳定失败测试。 */
class WebSearchApplicationServiceTest {

    @Test
    void normalizesSafeUrlsStripsHtmlAndDeduplicatesWithinProvider() {
        WebSearchProviderPort bocha = new StubProvider(WebSearchProvider.BOCHA,
                new WebSearchProviderPort.RawSearchResult(List.of(
                        new WebSearchProviderPort.RawSearchHit(1, "<b>标题</b>",
                                "HTTPS://Example.com:443/path#fragment", "", "<em>摘要</em>", "昨天"),
                        new WebSearchProviderPort.RawSearchHit(2, "重复", "https://example.com/path", "example.com", "重复", null),
                        new WebSearchProviderPort.RawSearchHit(3, "恶意", "javascript:alert(1)", "evil", "x", null)), "trace"));
        WebSearchService service = new WebSearchApplicationService(bocha,
                new StubProvider(WebSearchProvider.SEARCH_API,
                        new WebSearchProviderPort.RawSearchResult(List.of(), null)));

        var result = service.search(WebSearchProvider.BOCHA,
                new WebSearchRequest("  问题\n", WebSearchFreshness.ANY, 5));

        assertThat(result.status()).isEqualTo(WebSearchStatus.SUCCESS);
        assertThat(result.hits()).singleElement().satisfies(hit -> {
            assertThat(hit.title()).isEqualTo("标题");
            assertThat(hit.url()).isEqualTo("https://example.com/path");
            assertThat(hit.site()).isEqualTo("example.com");
            assertThat(hit.snippet()).isEqualTo("摘要");
            assertThat(hit.dateLabel()).isEqualTo("昨天");
            assertThat(hit.retrievedAt()).isNotNull();
        });
    }

    @Test
    void mapsProviderFailureAndInvalidInputWithoutThrowing() {
        WebSearchProviderPort failing = new WebSearchProviderPort() {
            @Override
            public WebSearchProvider provider() {
                return WebSearchProvider.BOCHA;
            }

            @Override
            public RawSearchResult search(String query, WebSearchFreshness freshness, int count) {
                throw new WebSearchProviderException(WebSearchReason.RATE_LIMITED, "stub");
            }
        };
        WebSearchService service = new WebSearchApplicationService(failing,
                new StubProvider(WebSearchProvider.SEARCH_API,
                        new WebSearchProviderPort.RawSearchResult(List.of(), null)));

        var rateLimited = service.search(WebSearchProvider.BOCHA,
                new WebSearchRequest("query", WebSearchFreshness.ANY, 5));
        var invalid = service.search(WebSearchProvider.BOCHA,
                new WebSearchRequest(" ", WebSearchFreshness.ANY, 5));

        assertThat(rateLimited.status()).isEqualTo(WebSearchStatus.UNAVAILABLE);
        assertThat(rateLimited.reason()).isEqualTo(WebSearchReason.RATE_LIMITED);
        assertThat(invalid.reason()).isEqualTo(WebSearchReason.INVALID_QUERY);
    }

    @Test
    void keepsTheLowestProviderRankForNormalizedDuplicateUrls() {
        WebSearchProviderPort bocha = new StubProvider(WebSearchProvider.BOCHA,
                new WebSearchProviderPort.RawSearchResult(List.of(
                        new WebSearchProviderPort.RawSearchHit(5, "较晚结果",
                                "https://example.com/result", "example.com", "late", null),
                        new WebSearchProviderPort.RawSearchHit(1, "最前结果",
                                "https://example.com/result#fragment", "example.com", "first", null)), null));
        WebSearchService service = new WebSearchApplicationService(bocha,
                new StubProvider(WebSearchProvider.SEARCH_API,
                        new WebSearchProviderPort.RawSearchResult(List.of(), null)));

        var result = service.search(WebSearchProvider.BOCHA,
                new WebSearchRequest("query", WebSearchFreshness.ANY, 5));

        assertThat(result.hits()).singleElement().satisfies(hit -> {
            assertThat(hit.providerRank()).isEqualTo(1);
            assertThat(hit.title()).isEqualTo("最前结果");
        });
    }

    private record StubProvider(WebSearchProvider provider, RawSearchResult response)
            implements WebSearchProviderPort {
        @Override
        public RawSearchResult search(String query, WebSearchFreshness freshness, int count) {
            return response;
        }
    }
}
