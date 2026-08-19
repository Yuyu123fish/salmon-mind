package com.yuyu.salmonmind.codebase.application.port;

import java.nio.file.Path;

/**
 * 仓库路径边界的内部合同。
 *
 * <p>实现负责绝对路径、Git 根、真实路径、仓库内目标以及 Search Root 边界检查；应用层不拼接
 * 外部路径。</p>
 */
public interface RepositoryPathPort {

    /** 校验绝对目录并解析为真实 Git 工作树根；输入无效或非 Git 目录必须失败。 */
    Path requireRepositoryRoot(String input);

    /** 校验绝对 Search Root；不因授权目录而自动授权其下任意文件读取。 */
    Path requireSearchRoot(String input);

    /** 重新验证 catalog 记录的身份与真实 Git 根；仓库移动或替换时返回不可用。 */
    RepositoryLocation resolveRegistered(StoredRepository registration);

    /** 解析仓库内相对目标并执行分段边界、symlink/junction 和可读性检查。 */
    ResolvedPath resolveTarget(RepositoryLocation location, String path, boolean directoryRequired);

    boolean isWithin(Path root, Path candidate);

    String logicalPath(Path root, Path path);
}
