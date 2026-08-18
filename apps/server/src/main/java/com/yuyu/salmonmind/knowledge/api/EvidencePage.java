package com.yuyu.salmonmind.knowledge.api;

import java.util.List;

/**
 * Evidence 分页结果。page 从 0 开始，size 是请求页大小，total 是该 READY Revision
 * 的全部可见 Evidence 数量；items 不包含未发布 Job 的正文。
 */
public record EvidencePage(List<EvidencePreview> items, int page, int size, long total) {

    public EvidencePage {
        items = List.copyOf(items);
    }
}
