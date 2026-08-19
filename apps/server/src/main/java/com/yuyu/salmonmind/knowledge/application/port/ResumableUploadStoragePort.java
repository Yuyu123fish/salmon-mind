package com.yuyu.salmonmind.knowledge.application.port;

import java.nio.file.Path;
import java.time.Instant;

/**
 * 仅允许普通 S3 Object 操作的上传存储边界；不暴露 AWS SDK 类型，也不包含原生 Multipart API。
 */
public interface ResumableUploadStoragePort {

    /**
     * 以普通 S3 PutObject 写入一个已在本地完成长度和 SHA-256 校验的临时文件。
     * objectKey 必须来自上传模块拥有的 part/final 前缀；实现不得暴露或调用 Multipart API。
     *
     * @param file 要写入的本地文件，不得为目录
     * @param objectKey 服务端生成的精确对象键
     * @param mediaType 服务端已校验的媒体类型
     */
    void putObject(Path file, String objectKey, String mediaType);

    /**
     * 将单个普通 Object 流式下载到目标文件；目标已存在时覆盖。
     *
     * @param objectKey 服务端生成的精确对象键
     * @param target 接收对象字节的临时文件路径
     */
    void downloadObject(String objectKey, Path target);

    /**
     * 读取对象大小、媒体类型和可信 lastModified，用于 final 校验或孤儿清理年龄判断。
     *
     * @param objectKey 精确对象键
     * @return 对象当前快照
     */
    ObjectHead headObject(String objectKey);

    /**
     * 按受限前缀执行有界 ListObjectsV2 分页；调用方必须把返回 token 原样用于下一页。
     *
     * @param prefix 只能是模块拥有的版本化前缀
     * @param continuationToken 上一页返回的 token，首次为空
     * @param maxKeys 本页上限，实现必须再次限制到 SDK 允许范围
     * @return 当前页对象和下一页状态
     */
    ObjectPage listObjects(String prefix, String continuationToken, int maxKeys);

    /**
     * 精确删除一个对象；不存在时按幂等成功处理，不能接受宽泛前缀。
     *
     * @param objectKey 服务端生成的精确对象键
     */
    void deleteObject(String objectKey);

    /** 普通对象的只读快照；lastModified 缺失时实现必须返回可保守处理的值。 */
    record ObjectHead(String objectKey, long sizeBytes, String mediaType, Instant lastModified) {
    }

    /** 有界对象列表页；objects 不得为 null。 */
    record ObjectPage(java.util.List<ObjectHead> objects, String nextContinuationToken, boolean truncated) {
        public ObjectPage {
            objects = objects == null ? java.util.List.of() : java.util.List.copyOf(objects);
        }
    }
}
