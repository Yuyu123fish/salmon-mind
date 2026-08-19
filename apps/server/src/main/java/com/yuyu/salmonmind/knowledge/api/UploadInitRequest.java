package com.yuyu.salmonmind.knowledge.api;

/** 初始化可恢复上传所需的客户端文件指纹；不包含文件字节或客户端 Object Key。 */
public record UploadInitRequest(
        String fileName,
        String declaredMediaType,
        long sizeBytes,
        String fileFingerprint,
        long lastModifiedMillis
) {
}
