package com.yuyu.salmonmind.codebase.infrastructure.git;

import com.yuyu.salmonmind.codebase.api.CodebaseErrorCode;
import com.yuyu.salmonmind.codebase.api.CodebaseException;
import com.yuyu.salmonmind.codebase.application.port.GitProcessPort;
import com.yuyu.salmonmind.codebase.application.port.GitProcessPort.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Git 子进程的唯一启动入口。
 *
 * <p>命令始终由参数数组组成，工作目录由已解析的真实仓库根提供；关闭 pager、终端提示和可选
 * index lock，超时会终止整个子进程树。stderr 只用于服务端诊断，不向调用方回显。</p>
 */
@Component
public final class GitProcessRunner implements GitProcessPort {

    private static final int MAX_OUTPUT_BYTES = 8 * 1024 * 1024;
    private static final Set<String> READ_ONLY_COMMANDS = Set.of(
            "status", "rev-parse", "ls-files", "check-ignore", "diff", "diff-tree",
            "log", "show", "blame");

    private final String executable;
    private final Duration timeout;

    public GitProcessRunner(
            @Value("${codebase.git-command:git}") String executable,
            @Value("${codebase.git-timeout:10s}") Duration timeout
    ) {
        if (executable == null || executable.isBlank() || timeout == null
                || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(60)) > 0) {
            throw new IllegalArgumentException("CODEBASE Git 配置无效");
        }
        this.executable = executable;
        this.timeout = timeout;
    }

    @Override
    public Result run(Path workingDirectory, List<String> arguments) {
        if (workingDirectory == null || arguments == null || arguments.isEmpty()
                || arguments.stream().anyMatch(this::invalidArgument)
                || !isReadOnlyCommand(arguments)) {
            throw new CodebaseException(CodebaseErrorCode.INVALID_QUERY, "Git 查询参数不合法");
        }
        List<String> command = new ArrayList<>(arguments.size() + 2);
        command.add(executable);
        command.add("--no-pager");
        command.addAll(arguments);
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(workingDirectory.toFile());
            Map<String, String> environment = builder.environment();
            environment.put("GIT_OPTIONAL_LOCKS", "0");
            environment.put("GIT_TERMINAL_PROMPT", "0");
            environment.put("GIT_PAGER", "cat");
            environment.put("GIT_EXTERNAL_DIFF", "");
            process = builder.start();
        } catch (IOException ex) {
            throw new CodebaseException(CodebaseErrorCode.GIT_NOT_AVAILABLE,
                    "Git 命令不可用", ex);
        }

        Capture stdout = new Capture(process.getInputStream(), MAX_OUTPUT_BYTES);
        Capture stderr = new Capture(process.getErrorStream(), MAX_OUTPUT_BYTES);
        Thread stdoutThread = Thread.startVirtualThread(stdout);
        Thread stderrThread = Thread.startVirtualThread(stderr);
        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                terminate(process);
                join(stdoutThread);
                join(stderrThread);
                throw new CodebaseException(CodebaseErrorCode.GIT_QUERY_TIMEOUT,
                        "Git 查询超时");
            }
            join(stdoutThread);
            join(stderrThread);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            terminate(process);
            throw new CodebaseException(CodebaseErrorCode.GIT_QUERY_TIMEOUT,
                    "Git 查询被取消", ex);
        }
        return new Result(process.exitValue(), stdout.text(), stderr.text(), stdout.overflow(), stderr.overflow());
    }

    @Override
    public boolean isAvailable(Path workingDirectory) {
        try {
            Path directory = workingDirectory != null && Files.isDirectory(workingDirectory)
                    ? workingDirectory
                    : Path.of(".").toAbsolutePath().normalize();
            return run(directory, List.of("--version")).succeeded();
        } catch (CodebaseException ex) {
            return false;
        }
    }

    private boolean isReadOnlyCommand(List<String> arguments) {
        String command = arguments.getFirst();
        return READ_ONLY_COMMANDS.contains(command)
                || ("--version".equals(command) && arguments.size() == 1);
    }

    private boolean invalidArgument(String argument) {
        return argument == null || argument.indexOf('\0') >= 0;
    }

    private static void terminate(Process process) {
        process.descendants().forEach(handle -> {
            handle.destroy();
            handle.destroyForcibly();
        });
        process.destroy();
        process.destroyForcibly();
    }

    private static void join(Thread thread) {
        try {
            thread.join(TimeUnit.SECONDS.toMillis(2));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class Capture implements Runnable {
        private final InputStream input;
        private final int maxBytes;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private boolean overflow;

        private Capture(InputStream input, int maxBytes) {
            this.input = input;
            this.maxBytes = maxBytes;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[8192];
            try (input) {
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    int remaining = maxBytes - output.size();
                    if (remaining <= 0) {
                        overflow = true;
                        continue;
                    }
                    int accepted = Math.min(remaining, count);
                    output.write(buffer, 0, accepted);
                    if (accepted < count) {
                        overflow = true;
                    }
                }
            } catch (IOException ex) {
                overflow = true;
            }
        }

        private String text() {
            return output.toString(StandardCharsets.UTF_8);
        }

        private boolean overflow() {
            return overflow;
        }
    }
}
