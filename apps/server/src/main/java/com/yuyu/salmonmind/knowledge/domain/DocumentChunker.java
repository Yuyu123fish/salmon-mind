package com.yuyu.salmonmind.knowledge.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * chunk-v1：优先保留标题/段落边界，超长段落才按字符窗口切分，并保留固定重叠。
 * 该规则是纯领域逻辑，便于在不启动外部基础设施时验证边界。
 */
public final class DocumentChunker {

    public static final int DEFAULT_MAX_CHARS = 1200;
    public static final int DEFAULT_OVERLAP_CHARS = 150;

    private final int maxChars;
    private final int overlapChars;

    public DocumentChunker() {
        this(DEFAULT_MAX_CHARS, DEFAULT_OVERLAP_CHARS);
    }

    public DocumentChunker(int maxChars, int overlapChars) {
        if (maxChars <= 0 || overlapChars < 0 || overlapChars >= maxChars) {
            throw new IllegalArgumentException("切片窗口参数无效");
        }
        this.maxChars = maxChars;
        this.overlapChars = overlapChars;
    }

    public List<DocumentChunk> chunk(String input) {
        if (input == null || input.isBlank()) {
            return List.of();
        }
        String normalized = input.replace("\r\n", "\n").replace('\r', '\n').trim();
        List<String> sections = splitSections(normalized);
        List<DocumentChunk> chunks = new ArrayList<>();
        int ordinal = 0;
        for (int sectionIndex = 0; sectionIndex < sections.size(); sectionIndex++) {
            String section = sections.get(sectionIndex).trim();
            if (section.isEmpty()) {
                continue;
            }
            int start = 0;
            while (start < section.length()) {
                int end = Math.min(section.length(), start + maxChars);
                if (end < section.length()) {
                    int boundary = lastBoundary(section, start, end);
                    if (boundary > start + Math.min(32, maxChars / 4)) {
                        end = boundary;
                    }
                }
                String text = section.substring(start, end).trim();
                if (!text.isEmpty()) {
                    chunks.add(new DocumentChunk(ordinal++, "section " + (sectionIndex + 1), text));
                }
                if (end >= section.length()) {
                    break;
                }
                int next = Math.max(start + 1, end - overlapChars);
                start = next;
            }
        }
        return List.copyOf(chunks);
    }

    private static List<String> splitSections(String text) {
        String[] raw = text.split("\\n\\s*\\n+");
        List<String> result = new ArrayList<>();
        for (String part : raw) {
            String section = part.trim();
            if (!section.isEmpty()) {
                result.add(section);
            }
        }
        return result;
    }

    private static int lastBoundary(String text, int start, int end) {
        int newline = text.lastIndexOf('\n', end);
        int space = text.lastIndexOf(' ', end);
        return Math.max(newline, space);
    }
}
