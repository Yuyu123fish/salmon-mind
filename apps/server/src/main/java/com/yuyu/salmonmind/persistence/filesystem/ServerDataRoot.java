package com.yuyu.salmonmind.persistence.filesystem;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * SalmonMind 使用数据的唯一文件系统根。
 *
 * <p>本地开发没有显式配置时，从启动工作目录向上定位包含 {@code compose.yaml} 和
 * {@code apps/server/pom.xml} 的项目根，再使用项目根 {@code data/}。Conversation 与
 * codebase 只从这里派生各自子目录，避免 IDE、CLI 和容器以不同工作目录启动时产生
 * 多份相对数据。该类只负责解析、校验和创建 Server-owned 目录，不参与业务数据格式。</p>
 */
@Component
public final class ServerDataRoot {

    /** Spring 配置中表示环境变量未设置的稳定哨兵值。 */
    public static final String UNSET = "__SALMON_DATA_DIR_UNSET__";

    private final Path root;
    private final Path conversationRoot;
    private final Path repositoryUnderstandingRoot;

    @org.springframework.beans.factory.annotation.Autowired
    public ServerDataRoot(
            @Value("${salmon.data-dir:" + UNSET + "}") String configuredDataDir,
            @Value("${CONVERSATION_DATA_DIR:" + UNSET + "}") String legacyConversationDataDir,
            @Value("${CODEBASE_DATA_DIR:" + UNSET + "}") String legacyCodebaseDataDir
    ) {
        this(configuredDataDir, Path.of(System.getProperty("user.dir", ".")),
                legacyConversationDataDir, legacyCodebaseDataDir);
    }

    /** 测试和启动诊断使用的显式工作目录 seam；不会读取或修改目标仓库。 */
    public ServerDataRoot(String configuredDataDir, Path workingDirectory) {
        this(configuredDataDir, workingDirectory, UNSET, UNSET);
    }

    public ServerDataRoot(
            String configuredDataDir,
            Path workingDirectory,
            String legacyConversationDataDir,
            String legacyCodebaseDataDir
    ) {
        rejectLegacyConfiguration(legacyConversationDataDir, "CONVERSATION_DATA_DIR");
        rejectLegacyConfiguration(legacyCodebaseDataDir, "CODEBASE_DATA_DIR");
        Path resolved = resolveConfiguredRoot(configuredDataDir, workingDirectory);
        ensureDirectory(resolved);
        root = realPath(resolved);
        conversationRoot = root.resolve("conversations");
        repositoryUnderstandingRoot = root.resolve("repository-understanding");
        ensureDirectory(conversationRoot);
        ensureDirectory(repositoryUnderstandingRoot);
    }

    public Path root() {
        return root;
    }

    public Path conversationRoot() {
        return conversationRoot;
    }

    public Path repositoryUnderstandingRoot() {
        return repositoryUnderstandingRoot;
    }

    private static Path resolveConfiguredRoot(String configuredDataDir, Path workingDirectory) {
        if (configuredDataDir != null && !UNSET.equals(configuredDataDir)) {
            if (configuredDataDir.isBlank()) {
                throw configurationError("SALMON_DATA_DIR 不能是空路径");
            }
            Path configured;
            try {
                configured = Path.of(configuredDataDir);
            } catch (RuntimeException ex) {
                throw configurationError("SALMON_DATA_DIR 不是合法路径", ex);
            }
            if (!configured.isAbsolute()) {
                throw configurationError("SALMON_DATA_DIR 必须是绝对路径");
            }
            return configured.normalize();
        }
        Path projectRoot = findProjectRoot(workingDirectory);
        return projectRoot.resolve("data").normalize();
    }

    private static Path findProjectRoot(Path workingDirectory) {
        if (workingDirectory == null) {
            throw configurationError("无法确定启动工作目录；请设置绝对 SALMON_DATA_DIR");
        }
        Path current;
        try {
            current = workingDirectory.toAbsolutePath().normalize();
            if (Files.isRegularFile(current)) {
                current = current.getParent();
            }
        } catch (RuntimeException ex) {
            throw configurationError("无法确定启动工作目录；请设置绝对 SALMON_DATA_DIR", ex);
        }
        while (current != null) {
            if (Files.isRegularFile(current.resolve("compose.yaml"))
                    && Files.isRegularFile(current.resolve("apps/server/pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw configurationError(
                "无法从启动工作目录定位 SalmonMind 项目根；请设置绝对 SALMON_DATA_DIR");
    }

    private static void ensureDirectory(Path directory) {
        try {
            if (Files.exists(directory) && !Files.isDirectory(directory)) {
                throw configurationError("Server 数据根不是目录: " + directory.getFileName());
            }
            Files.createDirectories(directory);
            if (!Files.isDirectory(directory) || !Files.isWritable(directory)) {
                throw configurationError("Server 数据根不可写");
            }
        } catch (IOException | SecurityException ex) {
            throw configurationError("Server 数据根无法创建或写入", ex);
        }
    }

    private static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException ex) {
            throw configurationError("Server 数据根无法解析为真实路径", ex);
        }
    }

    private static void rejectLegacyConfiguration(String value, String variable) {
        if (value != null && !UNSET.equals(value)) {
            throw configurationError(variable + " 已废弃，请迁移到 SALMON_DATA_DIR 后重启 Server");
        }
    }

    private static IllegalStateException configurationError(String message) {
        return new IllegalStateException(message);
    }

    private static IllegalStateException configurationError(String message, Throwable cause) {
        return new IllegalStateException(message, cause);
    }
}
