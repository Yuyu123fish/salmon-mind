package com.yuyu.salmonmind.conversation.infrastructure.jsonl;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.TimeUnit;

/**
 * JSONL 文件的权威版本标记。长度识别追加和截断，修改时间与文件身份降低同长度替换的误判；
 * 它只用于缓存新鲜度判断，不承诺检测所有外部 bit rot。
 */
record JsonlAuthorityVersion(long size, long lastModifiedNanos, String fileKey) {

    static JsonlAuthorityVersion read(Path file) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            Object fileKey = attributes.fileKey();
            return new JsonlAuthorityVersion(
                    attributes.size(),
                    attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS),
                    fileKey == null ? null : fileKey.toString());
        } catch (IOException ex) {
            throw new UncheckedIOException("读取 Conversation JSONL 版本失败", ex);
        }
    }
}
