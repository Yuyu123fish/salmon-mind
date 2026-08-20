package com.yuyu.salmonmind.codebase.infrastructure.filesystem;

import com.yuyu.salmonmind.codebase.api.CodebaseErrorCode;
import com.yuyu.salmonmind.codebase.api.CodebaseException;
import com.yuyu.salmonmind.codebase.application.port.GitProcessPort;
import com.yuyu.salmonmind.codebase.application.port.RepositoryLocation;
import com.yuyu.salmonmind.codebase.application.port.RepositoryPathPort;
import com.yuyu.salmonmind.codebase.application.port.ResolvedPath;
import com.yuyu.salmonmind.codebase.application.port.StoredRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

/**
 * 负责把外部路径解析为真实 Git 工作树和仓库内目标。
 *
 * <p>所有授权判断都使用 {@link Path} 的分段关系与真实路径，而不是字符串前缀；
 * 每次查询重新解析 catalog 中的路径，仓库移动或被替换时不会继续读取新目标。</p>
 */
@Component
public final class RepositoryPathResolver implements RepositoryPathPort {

    private final GitProcessPort git;

    public RepositoryPathResolver(GitProcessPort git) {
        this.git = git;
    }

    @Override
    public Path requireRepositoryRoot(String input) {
        Path candidate = requireAbsolute(input);
        if (!Files.exists(candidate)) {
            throw new CodebaseException(CodebaseErrorCode.PATH_NOT_FOUND, "路径不存在");
        }
        if (!Files.isDirectory(candidate)) {
            throw new CodebaseException(CodebaseErrorCode.PATH_NOT_DIRECTORY, "路径不是目录");
        }
        if (!Files.isReadable(candidate)) {
            throw new CodebaseException(CodebaseErrorCode.PATH_NOT_READABLE, "路径不可读取");
        }
        Path realInput = realPath(candidate, CodebaseErrorCode.PATH_NOT_FOUND);
        return resolveGitRoot(realInput, true);
    }

    /** 从已经登记的路径重新确认真实 Git 根；任何身份变化都视为不可用。 */
    @Override
    public RepositoryLocation resolveRegistered(StoredRepository registration) {
        if (registration == null || !registration.registered()) {
            throw new CodebaseException(CodebaseErrorCode.REPOSITORY_NOT_FOUND, "仓库不存在");
        }
        Path stored;
        try {
            stored = Path.of(registration.path()).toAbsolutePath().normalize();
        } catch (InvalidPathException ex) {
            throw new CodebaseException(CodebaseErrorCode.CODEBASE_DATA_CORRUPTED,
                    "代码库 catalog 已损坏", ex);
        }
        if (!stored.isAbsolute() || !Files.exists(stored) || !Files.isDirectory(stored)
                || !Files.isReadable(stored)) {
            throw new CodebaseException(CodebaseErrorCode.REPOSITORY_UNAVAILABLE, "仓库当前不可访问");
        }
        Path real;
        try {
            real = stored.toRealPath();
            if (!sameFileOrPath(real, stored)) {
                throw new CodebaseException(CodebaseErrorCode.REPOSITORY_UNAVAILABLE, "仓库当前不可访问");
            }
        } catch (IOException ex) {
            throw new CodebaseException(CodebaseErrorCode.REPOSITORY_UNAVAILABLE, "仓库当前不可访问", ex);
        }
        Path currentRoot;
        try {
            currentRoot = resolveGitRoot(real, false);
        } catch (CodebaseException ex) {
            if (ex.code() == CodebaseErrorCode.GIT_NOT_AVAILABLE
                    || ex.code() == CodebaseErrorCode.NOT_GIT_REPOSITORY
                    || ex.code() == CodebaseErrorCode.BARE_REPOSITORY_NOT_SUPPORTED) {
                throw new CodebaseException(CodebaseErrorCode.REPOSITORY_UNAVAILABLE, "仓库当前不可访问", ex);
            }
            throw ex;
        }
        try {
            if (!sameFileOrPath(currentRoot, real)) {
                throw new CodebaseException(CodebaseErrorCode.REPOSITORY_UNAVAILABLE, "仓库当前不可访问");
            }
        } catch (IOException ex) {
            throw new CodebaseException(CodebaseErrorCode.REPOSITORY_UNAVAILABLE, "仓库当前不可访问", ex);
        }
        return new RepositoryLocation(registration.id(), real, registration);
    }

    @Override
    public ResolvedPath resolveTarget(RepositoryLocation location, String path, boolean directoryRequired) {
        if (location == null) {
            throw new CodebaseException(CodebaseErrorCode.REPOSITORY_NOT_FOUND, "仓库不存在");
        }
        String requested = path == null || path.isBlank() ? "" : path;
        Path raw;
        try {
            raw = Path.of(requested.replace('/', java.io.File.separatorChar));
        } catch (InvalidPathException ex) {
            throw new CodebaseException(CodebaseErrorCode.INVALID_QUERY, "路径参数不合法");
        }
        Path normalized = raw.isAbsolute()
                ? raw.normalize()
                : location.root().resolve(raw).normalize();
        if (!isWithin(location.root(), normalized)) {
            throw new CodebaseException(CodebaseErrorCode.PATH_OUTSIDE_REPOSITORY,
                    "路径超出仓库边界");
        }
        if (!Files.exists(normalized)) {
            throw new CodebaseException(CodebaseErrorCode.PATH_NOT_FOUND, "路径不存在");
        }
        if (directoryRequired && !Files.isDirectory(normalized)) {
            throw new CodebaseException(CodebaseErrorCode.PATH_NOT_DIRECTORY, "路径不是目录");
        }
        if (!directoryRequired && Files.isDirectory(normalized)) {
            throw new CodebaseException(CodebaseErrorCode.UNSUPPORTED_TEXT_FILE, "目标不是文本文件");
        }
        if (!Files.isReadable(normalized)) {
            throw new CodebaseException(CodebaseErrorCode.PATH_NOT_READABLE, "路径不可读取");
        }
        Path real = realPath(normalized, CodebaseErrorCode.PATH_NOT_FOUND);
        if (!isWithin(location.root(), real)) {
            throw new CodebaseException(CodebaseErrorCode.PATH_OUTSIDE_REPOSITORY,
                    "路径超出仓库边界");
        }
        String logical = logicalPath(location.root(), normalized);
        String realLogical = logicalPath(location.root(), real);
        return new ResolvedPath(logical, realLogical, real);
    }

    @Override
    public boolean isWithin(Path root, Path candidate) {
        return candidate.equals(root) || candidate.startsWith(root);
    }

    @Override
    public String logicalPath(Path root, Path path) {
        return root.relativize(path.normalize()).toString().replace(java.io.File.separatorChar, '/');
    }

    private Path resolveGitRoot(Path workingDirectory, boolean exposeRegistrationErrors) {
        GitProcessPort.Result workTree = git.run(workingDirectory,
                List.of("rev-parse", "--is-inside-work-tree"));
        if (!workTree.succeeded()) {
            if (exposeRegistrationErrors) {
                throw new CodebaseException(CodebaseErrorCode.NOT_GIT_REPOSITORY,
                        "路径不是 Git 工作树");
            }
            throw new CodebaseException(CodebaseErrorCode.NOT_GIT_REPOSITORY,
                    "仓库当前不可访问");
        }
        if (!"true".equalsIgnoreCase(workTree.stdout().trim())) {
            throw new CodebaseException(CodebaseErrorCode.BARE_REPOSITORY_NOT_SUPPORTED,
                    "不支持 bare repository");
        }
        GitProcessPort.Result topLevel = git.run(workingDirectory,
                List.of("rev-parse", "--show-toplevel"));
        if (!topLevel.succeeded() || topLevel.stdout().isBlank()) {
            if (exposeRegistrationErrors) {
                throw new CodebaseException(CodebaseErrorCode.NOT_GIT_REPOSITORY,
                        "路径不是 Git 工作树");
            }
            throw new CodebaseException(CodebaseErrorCode.REPOSITORY_UNAVAILABLE,
                    "仓库当前不可访问");
        }
        try {
            Path reported = Path.of(topLevel.stdout().trim());
            if (!reported.isAbsolute()) {
                throw new CodebaseException(CodebaseErrorCode.NOT_GIT_REPOSITORY,
                        "Git 工作树根无效");
            }
            Path root = reported.toRealPath();
            if (!Files.isDirectory(root) || !isWithin(root, workingDirectory)) {
                throw new CodebaseException(CodebaseErrorCode.PATH_OUTSIDE_REPOSITORY,
                        "Git 工作树根越界");
            }
            return root;
        } catch (IOException | InvalidPathException ex) {
            throw new CodebaseException(CodebaseErrorCode.NOT_GIT_REPOSITORY,
                    "Git 工作树根无效", ex);
        }
    }

    private Path requireAbsolute(String input) {
        if (input == null || input.isBlank()) {
            throw new CodebaseException(CodebaseErrorCode.INVALID_ABSOLUTE_PATH,
                    "必须提供绝对路径");
        }
        try {
            Path path = Path.of(input);
            if (!path.isAbsolute()) {
                throw new CodebaseException(CodebaseErrorCode.INVALID_ABSOLUTE_PATH,
                        "必须提供绝对路径");
            }
            return path.normalize();
        } catch (InvalidPathException ex) {
            throw new CodebaseException(CodebaseErrorCode.INVALID_ABSOLUTE_PATH,
                    "绝对路径不合法");
        }
    }

    private Path realPath(Path path, CodebaseErrorCode errorCode) {
        try {
            return path.toRealPath();
        } catch (IOException ex) {
            throw new CodebaseException(errorCode, "路径当前不可访问", ex);
        }
    }

    private boolean sameFileOrPath(Path first, Path second) throws IOException {
        return first.equals(second) || Files.isSameFile(first, second);
    }

}
