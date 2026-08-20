package com.yuyu.salmonmind.codebase.application.port;

import java.nio.file.Path;
import java.util.List;

/**
 * Git 子进程适配器的唯一内部合同。
 *
 * <p>实现负责固定可读命令、超时、取消和输出上限；上层不能传入命令文本或自行启动进程。</p>
 */
public interface GitProcessPort {

    /** 在指定真实工作目录执行固定只读命令；参数非法、超时或启动失败映射为稳定异常。 */
    Result run(Path workingDirectory, List<String> arguments);

    /** 探测 Git 是否可启动；探测失败只返回 false，不向 catalog 写入诊断状态。 */
    boolean isAvailable(Path workingDirectory);

    /** 一次 Git 查询的有界输出与进程状态。 */
    record Result(int exitCode, String stdout, String stderr,
                  boolean stdoutOverflow, boolean stderrOverflow) {
        public boolean succeeded() {
            return exitCode == 0;
        }
    }
}
