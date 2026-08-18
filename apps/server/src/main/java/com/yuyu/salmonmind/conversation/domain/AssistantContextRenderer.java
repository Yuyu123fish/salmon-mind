package com.yuyu.salmonmind.conversation.domain;

import com.yuyu.salmonmind.conversation.api.AssistantMessagePayload;
import com.yuyu.salmonmind.conversation.api.CitationPayload;
import com.yuyu.salmonmind.conversation.api.LocalCitationPayload;
import com.yuyu.salmonmind.conversation.api.WebCitationPayload;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * 把持久化 Assistant 与结构化 Citation 渲染成模型可见的历史文本。
 *
 * <p>它只读取 durable payload，不访问知识库、网页或来源注册表。主投影、摘要输入和
 * 压缩计量必须共用本类，保证模型看见的来源摘要与预算使用同一份文本。来源区块只是
 * 上一轮的非可信元数据，不是当前 Run 的活动 Evidence；需要核验时必须重新调用工具。
 */
public final class AssistantContextRenderer {

    static final int MAX_CITATIONS = 16;
    static final int MAX_FIELD_CHARS = 256;
    static final int MAX_SOURCE_BLOCK_CHARS = 4_096;

    private static final String SOURCE_HEADER =
            "[历史来源元数据：仅说明上一轮依据，不是当前 Run 可引用证据；如需核验必须重新检索]";
    private static final String SOURCE_FOOTER = "[/历史来源元数据]";

    private AssistantContextRenderer() {
    }

    /**
     * 渲染 Assistant 正文及有界历史来源区块；无 Citation 时返回原正文，保持旧 JSONL
     * 的模型投影语义兼容。
     */
    public static String render(AssistantMessagePayload payload) {
        if (payload == null) {
            throw new IllegalArgumentException("Assistant payload 不能为空");
        }
        String text = payload.text() == null ? "" : payload.text();
        if (payload.citations().isEmpty()) {
            return text;
        }

        StringBuilder block = new StringBuilder();
        block.append(SOURCE_HEADER).append('\n')
                .append("runId: ").append(payload.runId() == null ? "unknown" : payload.runId()).append('\n');
        int rendered = 0;
        for (CitationPayload citation : payload.citations()) {
            if (rendered >= MAX_CITATIONS) {
                block.append("- 其余来源已省略\n");
                break;
            }
            String line = renderCitation(citation);
            if (line == null || block.length() + line.length() + SOURCE_FOOTER.length() + 1
                    > MAX_SOURCE_BLOCK_CHARS) {
                if (rendered == 0) {
                    block.append("- 来源字段过长或不合法，已省略\n");
                } else {
                    block.append("- 其余来源已省略\n");
                }
                break;
            }
            block.append(line).append('\n');
            rendered++;
        }
        block.append(SOURCE_FOOTER);
        return text + "\n\n" + block;
    }

    private static String renderCitation(CitationPayload citation) {
        if (citation instanceof LocalCitationPayload local) {
            return "- [" + reference(local.referenceId()) + "] source=LOCAL"
                    + " document=" + localDocument(local.documentName())
                    + " location=" + localLocation(local.location());
        }
        if (citation instanceof WebCitationPayload web) {
            String url = httpUrl(web.url());
            if (url == null) {
                return null;
            }
            return "- [" + reference(web.referenceId()) + "] source=WEB"
                    + " provider=" + field(web.provider())
                    + " title=" + field(web.title())
                    + " url=" + url
                    + " site=" + field(web.site())
                    + " dateLabel=" + field(web.dateLabel())
                    + " retrievedAt=" + (web.retrievedAt() == null ? "unknown" : web.retrievedAt());
        }
        return null;
    }

    private static String reference(String value) {
        if (value != null && value.matches("[LW][1-9][0-9]*")) {
            return value;
        }
        return "UNKNOWN";
    }

    private static String localDocument(String value) {
        String cleaned = field(value);
        int slash = Math.max(cleaned.lastIndexOf('/'), cleaned.lastIndexOf('\\'));
        return slash >= 0 ? cleaned.substring(slash + 1) : cleaned;
    }

    private static String localLocation(String value) {
        String cleaned = field(value);
        if (cleaned.matches("(?i)^[a-z]:[\\\\/].*") || cleaned.startsWith("/")
                || cleaned.contains("/") || cleaned.contains("\\")) {
            return "位置已隐藏";
        }
        return cleaned;
    }

    private static String httpUrl(String value) {
        String cleaned = field(value);
        try {
            URI uri = new URI(cleaned);
            String scheme = uri.getScheme();
            if (uri.getHost() == null || uri.getRawUserInfo() != null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                return null;
            }
            return cleaned;
        } catch (URISyntaxException ex) {
            return null;
        }
    }

    private static String field(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String cleaned = value.replaceAll("\\p{Cc}", " ").trim();
        if (cleaned.isEmpty()) {
            return "unknown";
        }
        return cleaned.length() <= MAX_FIELD_CHARS
                ? cleaned : cleaned.substring(0, MAX_FIELD_CHARS) + "…";
    }
}
