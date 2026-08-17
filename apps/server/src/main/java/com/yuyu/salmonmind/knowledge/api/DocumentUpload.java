package com.yuyu.salmonmind.knowledge.api;

import java.io.InputStream;
import java.util.Objects;

/**
 * 单文件上传的输入流合同。调用方负责在 {@link com.yuyu.salmonmind.knowledge.api.KnowledgeService#upload}
 * 返回后关闭输入流；Knowledge 会在方法内完成限额校验、摘要计算和原件落盘。
 */
public record DocumentUpload(String fileName, String declaredMediaType, InputStream content) {

    public DocumentUpload {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        Objects.requireNonNull(content, "content");
    }
}
