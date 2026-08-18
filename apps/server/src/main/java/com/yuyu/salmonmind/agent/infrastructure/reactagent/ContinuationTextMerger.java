package com.yuyu.salmonmind.agent.infrastructure.reactagent;

/**
 * 续写正文的确定性合并器。只移除上一段正文的精确后缀与新段前缀重叠，
 * 不做语义改写、不解析 Markdown，也不触碰 Citation 元数据。
 */
final class ContinuationTextMerger {

    private static final int SAFE_OVERLAP_CHARS = 8;

    private ContinuationTextMerger() {
    }

    static String appendedSuffix(String existing, String next) {
        if (next == null || next.isEmpty()) {
            return "";
        }
        if (existing == null || existing.isEmpty()) {
            return next;
        }
        if (existing.endsWith(next)) {
            return "";
        }
        int maximum = Math.min(existing.length(), next.length());
        for (int length = maximum; length >= SAFE_OVERLAP_CHARS; length--) {
            if (existing.regionMatches(existing.length() - length, next, 0, length)) {
                return next.substring(length);
            }
        }
        return next;
    }
}
