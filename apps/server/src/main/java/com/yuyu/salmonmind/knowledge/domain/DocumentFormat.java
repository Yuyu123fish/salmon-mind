package com.yuyu.salmonmind.knowledge.domain;

import java.util.Locale;
import java.util.Set;

/** Stage 02 支持的文档格式；扩展名只用于初步筛选，最终类型以内容探测为准。 */
public enum DocumentFormat {
    TEXT(Set.of("txt"), Set.of("text/plain")),
    MARKDOWN(Set.of("md", "markdown"), Set.of("text/markdown", "text/plain")),
    PDF(Set.of("pdf"), Set.of("application/pdf")),
    DOCX(Set.of("docx"), Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document"));

    private final Set<String> extensions;
    private final Set<String> mediaTypes;

    DocumentFormat(Set<String> extensions, Set<String> mediaTypes) {
        this.extensions = extensions;
        this.mediaTypes = mediaTypes;
    }

    public static DocumentFormat fromFileName(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot < 0 || dot == lower.length() - 1) {
            throw new IllegalArgumentException("文件缺少受支持的扩展名");
        }
        String extension = lower.substring(dot + 1);
        for (DocumentFormat format : values()) {
            if (format.extensions.contains(extension)) {
                return format;
            }
        }
        throw new IllegalArgumentException("不支持的文档格式");
    }

    public boolean acceptsMediaType(String mediaType) {
        if (mediaType == null || mediaType.isBlank()) {
            return false;
        }
        String normalized = mediaType.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
        return mediaTypes.stream().anyMatch(expected -> expected.endsWith("/*")
                ? normalized.startsWith(expected.substring(0, expected.length() - 1))
                : expected.equals(normalized));
    }

    /**
     * 判断 multipart 声明类型与内容探测类型是否表达同一类文档。
     * 文本格式允许 Tika 在 text/plain 与 text/markdown 之间细分；浏览器常见的
     * application/octet-stream 只表示“未声明”，此时以实际探测类型为准。
     */
    public boolean compatibleMediaTypes(String declared, String detected) {
        if (isUnknownDeclaration(declared)) {
            return acceptsMediaType(detected);
        }
        if (!acceptsMediaType(declared) || !acceptsMediaType(detected)) {
            return false;
        }
        String declaredType = normalize(declared);
        String detectedType = normalize(detected);
        if (declaredType.equals(detectedType)) {
            return true;
        }
        return declaredType.startsWith("text/") && detectedType.startsWith("text/");
    }

    private static boolean isUnknownDeclaration(String mediaType) {
        return mediaType == null || mediaType.isBlank()
                || "application/octet-stream".equals(normalize(mediaType));
    }

    private static String normalize(String mediaType) {
        return mediaType.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
    }
}
