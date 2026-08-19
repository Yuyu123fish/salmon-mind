package com.yuyu.salmonmind.codebase.application.port;

import java.nio.file.Path;
import java.util.UUID;

/**
 * 路径适配器重新解析出的真实工作树位置。
 *
 * <p>应用层只能通过此对象继续查询，不把 catalog 中的字符串路径直接当作权限边界。</p>
 */
public record RepositoryLocation(UUID id, Path root, StoredRepository registration) {
}
