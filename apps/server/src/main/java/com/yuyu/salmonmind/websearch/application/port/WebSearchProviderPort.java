package com.yuyu.salmonmind.websearch.application.port;

import com.yuyu.salmonmind.websearch.api.WebSearchService.WebSearchFreshness;
import com.yuyu.salmonmind.websearch.api.WebSearchService.WebSearchProvider;

import java.util.List;

/** WebSearch 模块内部的 Provider 变化轴；Adapter 不负责 fallback 或重试。 */
public interface WebSearchProviderPort {

    WebSearchProvider provider();

    RawSearchResult search(String query, WebSearchFreshness freshness, int count);

    record RawSearchResult(List<RawSearchHit> hits, String traceId) {
        public RawSearchResult {
            hits = hits == null ? List.of() : List.copyOf(hits);
        }
    }

    record RawSearchHit(
            int rank,
            String title,
            String url,
            String site,
            String snippet,
            String dateLabel
    ) {
    }
}
