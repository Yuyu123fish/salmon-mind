package com.yuyu.salmonmind.websearch.api;

import java.time.Instant;
import java.util.List;

/**
 * SalmonMind 的网页搜索公开合同。调用方通过 SearchApi.io 取得已归一化的自然结果，
 * 不接触 Provider 的 HTTP DTO、原始错误体或生成式答案。
 */
public interface WebSearchService {

    /**
     * 查询网页自然结果。当前实现固定使用 SearchApi.io，不做 Provider fallback 或重试。
     * 未配置、鉴权失败、限流、超时和非法响应均以结构化 {@link WebSearchResult} 返回。
     */
    WebSearchResult search(WebSearchRequest request);

    enum WebSearchProvider {
        SEARCH_API
    }

    enum WebSearchFreshness {
        ANY,
        DAY,
        WEEK,
        MONTH,
        YEAR
    }

    enum WebSearchStatus {
        SUCCESS,
        EMPTY,
        UNAVAILABLE
    }

    enum WebSearchReason {
        NONE,
        INVALID_QUERY,
        NOT_CONFIGURED,
        AUTH_FAILED,
        RATE_LIMITED,
        TIMEOUT,
        PROVIDER_FAILED,
        INVALID_RESPONSE
    }

    /**
     * 搜索输入。应用服务会再次规范化 query、补齐 freshness/count 默认值并执行边界校验。
     * count 为 0 表示使用默认值 5，合法显式范围为 1 到 10。
     */
    record WebSearchRequest(String query, WebSearchFreshness freshness, int count) {
    }

    /** Provider 无关的搜索结果；retrievedAt 是 Server 收到响应的精确时间。 */
    record WebSearchResult(
            WebSearchProvider provider,
            WebSearchStatus status,
            WebSearchReason reason,
            List<WebSearchHit> hits,
            String traceId
    ) {
        public WebSearchResult {
            hits = hits == null ? List.of() : List.copyOf(hits);
        }
    }

    /**
     * 已通过 URL 和字段边界校验的自然搜索结果。dateLabel 保留 Provider 的相对日期，
     * 不被解释为精确时间；只有 retrievedAt 承担精确检索时间语义。
     */
    record WebSearchHit(
            WebSearchProvider provider,
            int providerRank,
            String title,
            String url,
            String site,
            String snippet,
            String dateLabel,
            Instant retrievedAt
    ) {
    }
}
