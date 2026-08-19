package com.yuyu.salmonmind.codebase.application.port;

import java.nio.file.Path;

/** 路径适配器完成边界与真实路径校验后的仓库内目标。 */
public record ResolvedPath(String requestedPath, String realPath, Path real) {
}
