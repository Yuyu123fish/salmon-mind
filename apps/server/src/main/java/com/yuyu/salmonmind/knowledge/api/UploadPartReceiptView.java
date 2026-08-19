package com.yuyu.salmonmind.knowledge.api;

import java.time.Instant;

/** 对外可见的 Part Receipt，不暴露内部 Object Key。 */
public record UploadPartReceiptView(int partNumber, long sizeBytes, String sha256, Instant confirmedAt) {
}
