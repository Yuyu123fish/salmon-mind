package com.yuyu.salmonmind.websearch.application;

import com.yuyu.salmonmind.websearch.api.WebSearchService;
import com.yuyu.salmonmind.websearch.api.WebSearchService.WebSearchFreshness;
import com.yuyu.salmonmind.websearch.api.WebSearchService.WebSearchHit;
import com.yuyu.salmonmind.websearch.api.WebSearchService.WebSearchProvider;
import com.yuyu.salmonmind.websearch.api.WebSearchService.WebSearchReason;
import com.yuyu.salmonmind.websearch.api.WebSearchService.WebSearchRequest;
import com.yuyu.salmonmind.websearch.api.WebSearchService.WebSearchResult;
import com.yuyu.salmonmind.websearch.api.WebSearchService.WebSearchStatus;
import com.yuyu.salmonmind.websearch.application.port.WebSearchProviderException;
import com.yuyu.salmonmind.websearch.application.port.WebSearchProviderPort;
import com.yuyu.salmonmind.websearch.application.port.WebSearchProviderPort.RawSearchHit;
import com.yuyu.salmonmind.websearch.application.port.WebSearchProviderPort.RawSearchResult;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 网页搜索的唯一应用编排器。它把 SearchApi.io 的原始结果收敛成边界合同，负责 URL
 * 安全、字段裁剪、去重和稳定失败；不做 Provider fallback 或结果融合。
 */
@Service
class WebSearchApplicationService implements WebSearchService {

    static final int DEFAULT_COUNT = 5;
    static final int MAX_COUNT = 10;
    static final int MAX_QUERY_CHARS = 2_000;
    private static final int MAX_TITLE_CHARS = 500;
    private static final int MAX_SITE_CHARS = 200;
    private static final int MAX_SNIPPET_CHARS = 4_000;
    private static final int MAX_DATE_LABEL_CHARS = 120;
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]*>");
    private static final WebSearchProvider PROVIDER = WebSearchProvider.SEARCH_API;

    private final WebSearchProviderPort searchApiWebSearchAdapter;

    WebSearchApplicationService(
            @Qualifier("searchApiWebSearchAdapter") WebSearchProviderPort searchApiWebSearchAdapter
    ) {
        this.searchApiWebSearchAdapter = searchApiWebSearchAdapter;
    }

    @Override
    public WebSearchResult search(WebSearchRequest request) {
        NormalizedRequest normalized = normalize(request);
        if (normalized == null) {
            return unavailable(WebSearchReason.INVALID_QUERY);
        }
        RawSearchResult raw;
        try {
            raw = searchApiWebSearchAdapter.search(
                    normalized.query(), normalized.freshness(), normalized.count());
        } catch (WebSearchProviderException ex) {
            return unavailable(ex.reason());
        } catch (RuntimeException ex) {
            return unavailable(WebSearchReason.PROVIDER_FAILED);
        }
        if (raw == null || raw.hits() == null) {
            return unavailable(WebSearchReason.INVALID_RESPONSE);
        }
        List<WebSearchHit> hits = normalizeHits(PROVIDER, raw.hits(), normalized.count());
        if (!raw.hits().isEmpty() && hits.isEmpty()) {
            return unavailable(WebSearchReason.INVALID_RESPONSE);
        }
        return new WebSearchResult(
                PROVIDER,
                hits.isEmpty() ? WebSearchStatus.EMPTY : WebSearchStatus.SUCCESS,
                WebSearchReason.NONE,
                hits,
                safeTrace(raw.traceId()));
    }

    static String normalizeQuery(String query) {
        if (query == null) {
            return null;
        }
        String normalized = query.replaceAll("\\p{Cc}", " ").replaceAll("\\s+", " ").trim();
        return normalized.isBlank() || normalized.length() > MAX_QUERY_CHARS ? null : normalized;
    }

    private static NormalizedRequest normalize(WebSearchRequest request) {
        if (request == null) {
            return null;
        }
        String query = normalizeQuery(request.query());
        WebSearchFreshness freshness = request.freshness() == null
                ? WebSearchFreshness.ANY : request.freshness();
        int count = request.count() == 0 ? DEFAULT_COUNT : request.count();
        if (query == null || count < 1 || count > MAX_COUNT) {
            return null;
        }
        return new NormalizedRequest(query, freshness, count);
    }

    private static List<WebSearchHit> normalizeHits(
            WebSearchProvider provider, List<RawSearchHit> rawHits, int count
    ) {
        Map<String, WebSearchHit> unique = new LinkedHashMap<>();
        int fallbackRank = 1;
        for (RawSearchHit raw : rawHits) {
            if (raw == null) {
                continue;
            }
            int rank = raw.rank() > 0 ? raw.rank() : fallbackRank;
            fallbackRank++;
            String title = clean(raw.title(), MAX_TITLE_CHARS);
            String url = normalizeUrl(raw.url());
            if (title.isBlank() || url == null) {
                continue;
            }
            String site = clean(raw.site(), MAX_SITE_CHARS);
            if (site.isBlank()) {
                site = hostOf(url);
            }
            String snippet = clean(raw.snippet(), MAX_SNIPPET_CHARS);
            String dateLabel = clean(raw.dateLabel(), MAX_DATE_LABEL_CHARS);
            WebSearchHit candidate = new WebSearchHit(provider, rank, title, url, site, snippet,
                    dateLabel.isBlank() ? null : dateLabel, Instant.now());
            WebSearchHit existing = unique.get(url);
            if (existing == null || candidate.providerRank() < existing.providerRank()) {
                unique.put(url, candidate);
            }
        }
        return unique.values().stream()
                .sorted(Comparator.comparingInt(WebSearchHit::providerRank))
                .limit(count)
                .toList();
    }

    static String normalizeUrl(String value) {
        if (value == null || value.isBlank() || value.chars().anyMatch(Character::isISOControl)) {
            return null;
        }
        try {
            URI uri = new URI(value.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || uri.getRawUserInfo() != null) {
                return null;
            }
            int port = uri.getPort();
            if (("http".equalsIgnoreCase(scheme) && port == 80)
                    || ("https".equalsIgnoreCase(scheme) && port == 443)) {
                port = -1;
            }
            StringBuilder normalized = new StringBuilder()
                    .append(scheme.toLowerCase(Locale.ROOT)).append("://")
                    .append(host.toLowerCase(Locale.ROOT));
            if (port >= 0) {
                normalized.append(':').append(port);
            }
            if (uri.getRawPath() != null && !uri.getRawPath().isEmpty()) {
                normalized.append(uri.getRawPath());
            } else {
                normalized.append('/');
            }
            if (uri.getRawQuery() != null) {
                normalized.append('?').append(uri.getRawQuery());
            }
            return normalized.toString();
        } catch (URISyntaxException ex) {
            return null;
        }
    }

    private static String clean(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        String cleaned = HTML_TAG.matcher(value)
                .replaceAll(" ")
                .replaceAll("\\p{Cc}", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned.length() <= maxChars ? cleaned : cleaned.substring(0, maxChars);
    }

    private static String hostOf(String url) {
        try {
            return URI.create(url).getHost();
        } catch (RuntimeException ex) {
            return "";
        }
    }

    private static String safeTrace(String traceId) {
        if (traceId == null || traceId.isBlank() || traceId.length() > 120
                || traceId.chars().anyMatch(Character::isISOControl)) {
            return null;
        }
        return traceId;
    }

    private static WebSearchResult unavailable(WebSearchReason reason) {
        return new WebSearchResult(PROVIDER, WebSearchStatus.UNAVAILABLE, reason, List.of(), null);
    }

    private record NormalizedRequest(String query, WebSearchFreshness freshness, int count) {
    }
}
