package com.yuyu.salmonmind.knowledge.application.port;

import java.nio.file.Path;

/** Knowledge 原件存储 Adapter；Object Key 只在模块内部流转。 */
public interface ObjectStoragePort {

    /** 写入不可变原件；失败时不得创建可见 Revision。 */
    void put(Path file, String objectKey, String mediaType);

    /** 将原件覆盖写入目标临时文件，供 Worker 解析。 */
    void download(String objectKey, Path target);

    /** 仅清理由当前提交产生的已知孤儿对象，失败必须可诊断且不得扩大范围。 */
    void deleteBestEffort(String objectKey);

    /**
     * 严格删除一个已由 PostgreSQL 冻结的 Object Key，并确认删除后不可读取；
     * 对象不存在视为成功，连接、鉴权和版本化存储语义不明确时必须失败。
     */
    void deleteStrict(String objectKey);
}
