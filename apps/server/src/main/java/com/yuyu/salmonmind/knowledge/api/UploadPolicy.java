package com.yuyu.salmonmind.knowledge.api;

/** 服务端上传策略；Web 仅据此选择旧单请求或可恢复 Session。 */
public record UploadPolicy(
        boolean resumableEnabled,
        long maxObjectBytes,
        long resumableThresholdBytes,
        int partSizeBytes,
        int maxConcurrentParts
) {
}
