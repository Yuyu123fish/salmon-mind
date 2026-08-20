package com.yuyu.salmonmind.codebase.infrastructure.filesystem;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyu.salmonmind.codebase.api.CodebaseErrorCode;
import com.yuyu.salmonmind.codebase.api.CodebaseException;
import com.yuyu.salmonmind.codebase.application.port.CatalogState;
import com.yuyu.salmonmind.codebase.application.port.CatalogStorePort;
import com.yuyu.salmonmind.codebase.application.port.StoredRepository;
import com.yuyu.salmonmind.persistence.filesystem.ServerDataRoot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * codebase catalog 的本地文件权威存储。
 *
 * <p>仓库注册资料和 settings 只写统一 Server Data Root 的
 * {@code repository-understanding/} 子目录。settings 从旧 v1 读取时只校验其 JSON
 * 形状和 Search Root 字段，不访问那些路径，然后原子收敛为只保留 Active 的 v2。</p>
 */
@Component
public final class CatalogStore implements CatalogStorePort {

    private static final int REPOSITORY_FORMAT_VERSION = 1;
    private static final int SETTINGS_FORMAT_VERSION = 2;
    private static final int LEGACY_SETTINGS_FORMAT_VERSION = 1;

    private final ObjectMapper mapper;
    private final Path dataDir;
    private final Path serverDataRoot;
    private final Path repositoriesDir;
    private CatalogState state;

    @Autowired
    public CatalogStore(ObjectMapper objectMapper, ServerDataRoot serverDataRoot) {
        this(objectMapper, serverDataRoot.repositoryUnderstandingRoot(), serverDataRoot.root());
    }

    /** 测试注入独立 catalog 目录；生产路径由 {@link ServerDataRoot} 统一派生。 */
    public CatalogStore(ObjectMapper objectMapper, String configuredDataDir) {
        Path data = absoluteConfiguredPath(configuredDataDir);
        this.mapper = objectMapper.copy().findAndRegisterModules();
        this.dataDir = data;
        this.serverDataRoot = data;
        this.repositoriesDir = data.resolve("repositories");
        this.state = load();
    }

    private CatalogStore(ObjectMapper objectMapper, Path dataDir, Path serverDataRoot) {
        this.mapper = objectMapper.copy().findAndRegisterModules();
        this.dataDir = dataDir.toAbsolutePath().normalize();
        this.serverDataRoot = serverDataRoot.toAbsolutePath().normalize();
        this.repositoriesDir = this.dataDir.resolve("repositories");
        this.state = load();
    }

    @Override
    public Path dataDir() {
        return dataDir;
    }

    @Override
    public Path serverDataRoot() {
        return serverDataRoot;
    }

    @Override
    public synchronized CatalogState snapshot() {
        return state;
    }

    @Override
    public synchronized void saveRepository(StoredRepository repository) {
        ensureDirectories();
        writeJson(repositoriesDir.resolve(repository.id().toString()).resolve("repository.json"),
                repositoryJson(repository));
        state = withRepository(repository);
    }

    @Override
    public synchronized void saveSettings(UUID activeRepositoryId) {
        ensureDirectories();
        writeJson(dataDir.resolve("settings.json"), settingsJson(activeRepositoryId));
        state = new CatalogState(state.repositories(), activeRepositoryId);
    }

    public synchronized void replaceState(CatalogState next) {
        ensureDirectories();
        writeJson(dataDir.resolve("settings.json"), settingsJson(next.activeRepositoryId()));
        for (StoredRepository repository : next.repositories().values()) {
            writeJson(repositoriesDir.resolve(repository.id().toString()).resolve("repository.json"),
                    repositoryJson(repository));
        }
        state = next;
    }

    private CatalogState load() {
        ensureDirectories();
        Map<UUID, StoredRepository> repositories = new HashMap<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(repositoriesDir)) {
            for (Path directory : entries) {
                if (!Files.isDirectory(directory)) {
                    throw corrupted("repositories 目录中存在非法条目");
                }
                Path repositoryFile = directory.resolve("repository.json");
                if (!Files.isRegularFile(repositoryFile)) {
                    throw corrupted("仓库 catalog 文件缺失");
                }
                StoredRepository repository = parseRepository(repositoryFile);
                if (!directory.getFileName().toString().equals(repository.id().toString())) {
                    throw corrupted("仓库目录与身份不一致");
                }
                if (repositories.put(repository.id(), repository) != null) {
                    throw corrupted("仓库身份重复");
                }
            }
        } catch (IOException ex) {
            throw new CodebaseException(CodebaseErrorCode.CODEBASE_DATA_UNAVAILABLE,
                    "代码库 catalog 数据目录不可用", ex);
        }
        validateRepositoryIdentities(repositories.values());

        UUID active = null;
        boolean migrateSettings = false;
        Path settings = dataDir.resolve("settings.json");
        if (Files.exists(settings)) {
            ParsedSettings parsed = parseSettings(settings);
            active = parsed.activeRepositoryId();
            migrateSettings = parsed.legacy();
        }
        validateActive(active, repositories);
        if (migrateSettings) {
            // 只有旧 JSON 完整通过校验且 Active 合法后才替换，损坏文件不会被覆盖。
            writeJson(settings, settingsJson(active));
        }
        return new CatalogState(repositories, active);
    }

    private void validateActive(UUID active, Map<UUID, StoredRepository> repositories) {
        if (active != null) {
            StoredRepository activeRepository = repositories.get(active);
            if (activeRepository == null || !activeRepository.registered()) {
                throw corrupted("Active Repository 不存在或未注册");
            }
        }
    }

    private void validateRepositoryIdentities(Iterable<StoredRepository> values) {
        List<StoredRepository> all = new ArrayList<>();
        values.forEach(all::add);
        Set<String> normalized = new HashSet<>();
        for (StoredRepository repository : all) {
            Path path;
            try {
                path = Path.of(repository.path()).toAbsolutePath().normalize();
            } catch (RuntimeException ex) {
                throw corrupted("仓库路径无效");
            }
            if (!path.isAbsolute() || repository.name() == null || repository.name().isBlank()
                    || !normalized.add(identityKey(path))) {
                throw corrupted("仓库身份或路径重复");
            }
            for (StoredRepository other : all) {
                if (repository.id().equals(other.id()) || repository == other) {
                    continue;
                }
                if (Files.exists(path) && Files.exists(Path.of(other.path()))) {
                    try {
                        if (Files.isSameFile(path, Path.of(other.path()))) {
                            throw corrupted("仓库真实路径重复");
                        }
                    } catch (IOException ex) {
                        // 路径暂时不可访问时保留 catalog；查询阶段再报告 unavailable。
                    }
                }
            }
        }
    }

    private CatalogState withRepository(StoredRepository repository) {
        Map<UUID, StoredRepository> next = new HashMap<>(state.repositories());
        next.put(repository.id(), repository);
        return new CatalogState(next, state.activeRepositoryId());
    }

    private StoredRepository parseRepository(Path file) {
        JsonNode node = readObject(file);
        requireFields(node, Set.of("formatVersion", "id", "path", "name", "aliases", "registered",
                "createdAt", "updatedAt"));
        if (node.path("formatVersion").asInt(-1) != REPOSITORY_FORMAT_VERSION) {
            throw corrupted("仓库 catalog 格式版本不受支持");
        }
        try {
            ObjectNode repositoryFields = (ObjectNode) node.deepCopy();
            repositoryFields.remove("formatVersion");
            StoredRepository result = mapper.treeToValue(repositoryFields, StoredRepository.class);
            if (result.id() == null || result.path() == null || result.createdAt() == null
                    || result.updatedAt() == null) {
                throw corrupted("仓库 catalog 字段缺失");
            }
            return result;
        } catch (IOException | RuntimeException ex) {
            if (ex instanceof CodebaseException codebaseException) {
                throw codebaseException;
            }
            throw new CodebaseException(CodebaseErrorCode.CODEBASE_DATA_CORRUPTED,
                    "代码库 catalog 已损坏", ex);
        }
    }

    private ParsedSettings parseSettings(Path file) {
        JsonNode node = readObject(file);
        int version = node.path("formatVersion").asInt(-1);
        try {
            if (version == SETTINGS_FORMAT_VERSION) {
                requireFields(node, Set.of("formatVersion", "activeRepositoryId"));
                return new ParsedSettings(parseActive(node), false);
            }
            if (version == LEGACY_SETTINGS_FORMAT_VERSION) {
                requireFields(node, Set.of("formatVersion", "activeRepositoryId", "searchRoots"));
                validateLegacySearchRoots(node.get("searchRoots"));
                return new ParsedSettings(parseActive(node), true);
            }
            throw corrupted("代码库 settings 格式版本不受支持");
        } catch (IOException | RuntimeException ex) {
            if (ex instanceof CodebaseException codebaseException) {
                throw codebaseException;
            }
            throw new CodebaseException(CodebaseErrorCode.CODEBASE_DATA_CORRUPTED,
                    "代码库 settings 已损坏", ex);
        }
    }

    private UUID parseActive(JsonNode node) {
        JsonNode activeNode = node.get("activeRepositoryId");
        if (activeNode == null || activeNode.isNull()) {
            return null;
        }
        if (!activeNode.isTextual() || activeNode.asText().isBlank()) {
            throw corrupted("Active Repository 字段无效");
        }
        try {
            return UUID.fromString(activeNode.asText());
        } catch (IllegalArgumentException ex) {
            throw corrupted("Active Repository 字段无效");
        }
    }

    /** 只校验 v1 的 Search Root JSON 结构，不读取或解析其指向的文件系统目录。 */
    private void validateLegacySearchRoots(JsonNode rootsNode) throws IOException {
        if (rootsNode == null || !rootsNode.isArray()) {
            throw corrupted("旧 Search Root catalog 字段无效");
        }
        Set<UUID> ids = new HashSet<>();
        Set<String> paths = new HashSet<>();
        for (JsonNode rootNode : rootsNode) {
            requireFields(rootNode, Set.of("id", "path", "createdAt"));
            UUID id;
            try {
                id = UUID.fromString(requiredText(rootNode, "id"));
                Instant.parse(requiredText(rootNode, "createdAt"));
            } catch (RuntimeException ex) {
                throw corrupted("旧 Search Root catalog 字段无效");
            }
            String rawPath = requiredText(rootNode, "path");
            Path path;
            try {
                path = Path.of(rawPath).toAbsolutePath().normalize();
            } catch (RuntimeException ex) {
                throw corrupted("旧 Search Root catalog 路径无效");
            }
            if (!path.isAbsolute() || !ids.add(id) || !paths.add(identityKey(path))) {
                throw corrupted("旧 Search Root catalog 身份或路径重复");
            }
        }
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw corrupted("catalog 字段无效");
        }
        return value.asText();
    }

    private JsonNode repositoryJson(StoredRepository repository) {
        var node = mapper.createObjectNode();
        node.put("formatVersion", REPOSITORY_FORMAT_VERSION);
        node.put("id", repository.id().toString());
        node.put("path", repository.path());
        node.put("name", repository.name());
        node.set("aliases", mapper.valueToTree(repository.aliases()));
        node.put("registered", repository.registered());
        node.put("createdAt", repository.createdAt().toString());
        node.put("updatedAt", repository.updatedAt().toString());
        return node;
    }

    private JsonNode settingsJson(UUID activeRepositoryId) {
        var node = mapper.createObjectNode();
        node.put("formatVersion", SETTINGS_FORMAT_VERSION);
        if (activeRepositoryId == null) {
            node.putNull("activeRepositoryId");
        } else {
            node.put("activeRepositoryId", activeRepositoryId.toString());
        }
        return node;
    }

    private JsonNode readObject(Path file) {
        try {
            JsonNode node = mapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
            if (node == null || !node.isObject()) {
                throw corrupted("catalog JSON 不是对象");
            }
            return node;
        } catch (CodebaseException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new CodebaseException(CodebaseErrorCode.CODEBASE_DATA_CORRUPTED,
                    "代码库 catalog 已损坏", ex);
        }
    }

    private void requireFields(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject()) {
            throw corrupted("catalog 字段结构无效");
        }
        var fields = node.fieldNames();
        while (fields.hasNext()) {
            if (!allowed.contains(fields.next())) {
                throw corrupted("catalog 包含未知字段");
            }
        }
        for (String required : allowed) {
            if (!node.has(required)) {
                throw corrupted("catalog 字段缺失");
            }
        }
    }

    private void ensureDirectories() {
        try {
            if (Files.exists(dataDir) && !Files.isDirectory(dataDir)) {
                throw new CodebaseException(CodebaseErrorCode.CODEBASE_DATA_UNAVAILABLE,
                        "代码库 catalog 数据目录不可用");
            }
            Files.createDirectories(repositoriesDir);
        } catch (CodebaseException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new CodebaseException(CodebaseErrorCode.CODEBASE_DATA_UNAVAILABLE,
                    "代码库 catalog 数据目录不可用", ex);
        }
    }

    private void writeJson(Path target, JsonNode value) {
        Path parent = target.getParent();
        try {
            Files.createDirectories(parent);
            Path temporary = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
            boolean moved = false;
            try {
                byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
                Files.write(temporary, bytes, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ex) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
                moved = true;
            } finally {
                if (!moved) {
                    Files.deleteIfExists(temporary);
                }
            }
        } catch (IOException ex) {
            throw new CodebaseException(CodebaseErrorCode.CODEBASE_DATA_UNAVAILABLE,
                    "代码库 catalog 数据目录不可用", ex);
        }
    }

    private static Path absoluteConfiguredPath(String configuredDataDir) {
        if (configuredDataDir == null || configuredDataDir.isBlank()) {
            throw new CodebaseException(CodebaseErrorCode.CODEBASE_DATA_UNAVAILABLE,
                    "代码库 catalog 数据目录不可用");
        }
        try {
            Path path = Path.of(configuredDataDir).normalize();
            if (!path.isAbsolute()) {
                throw new CodebaseException(CodebaseErrorCode.CODEBASE_DATA_UNAVAILABLE,
                        "代码库 catalog 数据目录必须是绝对路径");
            }
            return path;
        } catch (RuntimeException ex) {
            if (ex instanceof CodebaseException codebaseException) {
                throw codebaseException;
            }
            throw new CodebaseException(CodebaseErrorCode.CODEBASE_DATA_UNAVAILABLE,
                    "代码库 catalog 数据目录不可用", ex);
        }
    }

    private static CodebaseException corrupted(String message) {
        return new CodebaseException(CodebaseErrorCode.CODEBASE_DATA_CORRUPTED, message);
    }

    private static String identityKey(Path path) {
        String value = path.toString();
        return java.io.File.separatorChar == '\\'
                ? value.toLowerCase(java.util.Locale.ROOT)
                : value;
    }

    private record ParsedSettings(UUID activeRepositoryId, boolean legacy) {
    }
}
