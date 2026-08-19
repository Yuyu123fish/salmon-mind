package com.yuyu.salmonmind.codebase.api;

/** Server 所在平台提示；前端据此展示路径输入示例，不猜测另一台机器的路径。 */
public record PlatformView(
        String operatingSystem,
        String pathSeparator,
        boolean windows,
        String pathExample
) {
}
