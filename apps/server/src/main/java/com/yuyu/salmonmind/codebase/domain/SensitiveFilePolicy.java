package com.yuyu.salmonmind.codebase.domain;

import com.yuyu.salmonmind.codebase.api.CodebaseErrorCode;
import com.yuyu.salmonmind.codebase.api.CodebaseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 所有文件与 Git 内容入口共享的不可关闭敏感文件策略。
 *
 * <p>策略只根据规范化路径组件和文件名判断，不先读取内容再过滤；模板环境文件可读，
 * 真实环境、私钥、凭据目录、数据库备份及 Server 自有数据目录始终拒绝。</p>
 */
@Component
public final class SensitiveFilePolicy {

    private static final Set<String> TEMPLATE_ENV_NAMES = Set.of(
            ".env.example", ".env.sample", ".env.template", ".env.dist");
    private static final Set<String> KEY_NAMES = Set.of(
            "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519");
    private static final Set<String> SECRET_EXTENSIONS = Set.of(
            ".key", ".pem", ".p12", ".pfx", ".jks", ".keystore");
    private static final Set<String> BACKUP_EXTENSIONS = Set.of(
            ".db", ".sqlite", ".sqlite3", ".dump", ".bak", ".backup",
            ".db.gz", ".sqlite.gz", ".sqlite3.gz", ".dump.gz", ".bak.gz", ".backup.gz",
            ".db.zip", ".sqlite.zip", ".sqlite3.zip", ".dump.zip", ".bak.zip", ".backup.zip");

    private final List<Path> protectedRoots;

    @Autowired
    public SensitiveFilePolicy(
            @Value("${codebase.data-dir:data/repository-understanding}") String codebaseDataDir,
            @Value("${salmon.conversation.data-dir:data}") String conversationDataDir
    ) {
        protectedRoots = List.of(configuredRoot(codebaseDataDir), configuredRoot(conversationDataDir));
    }

    public SensitiveFilePolicy(List<Path> protectedRoots) {
        this.protectedRoots = protectedRoots.stream().map(this::normalizeRoot).toList();
    }

    public void ensureAllowed(Path repositoryRoot, String logicalPath, Path realPath) {
        if (isDenied(repositoryRoot, logicalPath, realPath)) {
            throw new CodebaseException(CodebaseErrorCode.SENSITIVE_FILE_DENIED,
                    "请求访问的文件受到保护");
        }
    }

    public boolean isDenied(Path repositoryRoot, String logicalPath, Path realPath) {
        if (realPath != null && isUnderProtectedRoot(realPath)) {
            return true;
        }
        String normalized = normalizeLogical(logicalPath);
        if (normalized.isBlank()) {
            return false;
        }
        if (repositoryRoot != null) {
            Path logicalTarget = repositoryRoot.resolve(normalized.replace('/', File.separatorChar)).normalize();
            if (isUnderProtectedRoot(logicalTarget)) {
                return true;
            }
        }
        String[] components = normalized.split("/");
        for (String component : components) {
            String lower = component.toLowerCase(Locale.ROOT);
            if (lower.equals(".git") || lower.equals(".ssh") || lower.equals(".gnupg")
                    || lower.equals(".aws") || lower.equals(".azure") || lower.equals(".kube")) {
                return true;
            }
            if (lower.equals(".docker") && normalized.toLowerCase(Locale.ROOT).endsWith(".docker/config.json")) {
                return true;
            }
        }
        String name = components[components.length - 1].toLowerCase(Locale.ROOT);
        if (name.equals("config") && normalized.toLowerCase(Locale.ROOT).endsWith(".kube/config")) {
            return true;
        }
        if (name.equals(".env")) {
            return true;
        }
        if (name.startsWith(".env.")) {
            return !TEMPLATE_ENV_NAMES.contains(name) && !containsTemplateMarker(name);
        }
        if (name.equals(".netrc") || name.equals(".npmrc") || name.equals(".pypirc")) {
            return true;
        }
        if ((name.contains("credential") || name.contains("secret")) && isConfigurationLike(name)) {
            return true;
        }
        if (name.matches("application-(dev|local)\\.[a-z0-9]+(?:\\.[a-z0-9]+)?")) {
            return true;
        }
        if (normalized.matches("(?i)(?:^|.*/)config/local\\.[^/]+")) {
            return true;
        }
        if (KEY_NAMES.stream().anyMatch(name::equals) || KEY_NAMES.stream().anyMatch(name::startsWith)) {
            return true;
        }
        if (SECRET_EXTENSIONS.stream().anyMatch(name::endsWith)) {
            return true;
        }
        return BACKUP_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    public String normalizeLogical(String logicalPath) {
        if (logicalPath == null) {
            return "";
        }
        String normalized = logicalPath.replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    private boolean isUnderProtectedRoot(Path realPath) {
        Path normalized = normalizeRoot(realPath);
        return protectedRoots.stream().anyMatch(root -> normalized.equals(root) || normalized.startsWith(root));
    }

    private boolean isConfigurationLike(String name) {
        return name.endsWith(".json") || name.endsWith(".yml") || name.endsWith(".yaml")
                || name.endsWith(".properties") || name.endsWith(".toml") || name.endsWith(".ini")
                || name.endsWith(".env") || name.endsWith(".conf") || name.endsWith(".config")
                || name.endsWith(".txt");
    }

    private boolean containsTemplateMarker(String name) {
        return name.contains("example") || name.contains("sample")
                || name.contains("template") || name.contains("dist");
    }

    private Path configuredRoot(String value) {
        if (value == null || value.isBlank()) {
            return Path.of(".").toAbsolutePath().normalize();
        }
        try {
            Path path = Path.of(value).toAbsolutePath().normalize();
            if (Files.exists(path)) {
                return path.toRealPath();
            }
            return path;
        } catch (Exception ex) {
            return Path.of(".").toAbsolutePath().normalize();
        }
    }

    private Path normalizeRoot(Path value) {
        return value.toAbsolutePath().normalize();
    }
}
