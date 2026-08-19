package com.yuyu.salmonmind.codebase.application;

import com.yuyu.salmonmind.codebase.api.CodebaseCatalogView;
import com.yuyu.salmonmind.codebase.api.CodebaseErrorCode;
import com.yuyu.salmonmind.codebase.api.CodebaseException;
import com.yuyu.salmonmind.codebase.api.CodebaseService;
import com.yuyu.salmonmind.codebase.api.PlatformView;
import com.yuyu.salmonmind.codebase.api.RepositoryView;
import com.yuyu.salmonmind.codebase.api.SearchRootView;
import com.yuyu.salmonmind.codebase.application.port.CatalogState;
import com.yuyu.salmonmind.codebase.application.port.CatalogStorePort;
import com.yuyu.salmonmind.codebase.application.port.GitObservation;
import com.yuyu.salmonmind.codebase.application.port.GitProcessPort;
import com.yuyu.salmonmind.codebase.application.port.GitQueryPort;
import com.yuyu.salmonmind.codebase.application.port.RepositoryLocation;
import com.yuyu.salmonmind.codebase.application.port.RepositoryPathPort;
import com.yuyu.salmonmind.codebase.application.port.StoredRepository;
import com.yuyu.salmonmind.codebase.application.port.StoredSearchRoot;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Repository catalog 的应用编排入口。
 *
 * <p>Mutation 在进程内串行化，并按“先发布仓库元数据、再发布 Active”执行；取消当前仓库
 * 则反过来先清空 Active。这样单次崩溃最多留下没有默认仓库，不会留下指向未注册对象的选择。</p>
 */
@Service
public final class RepositoryCatalogService implements CodebaseService {

    private final CatalogStorePort store;
    private final RepositoryPathPort paths;
    private final GitQueryPort git;
    private final GitProcessPort gitRunner;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public RepositoryCatalogService(
            CatalogStorePort store,
            RepositoryPathPort paths,
            GitQueryPort git,
            GitProcessPort gitRunner
    ) {
        this.store = store;
        this.paths = paths;
        this.git = git;
        this.gitRunner = gitRunner;
    }

    @Override
    public CodebaseCatalogView catalog() {
        lock.readLock().lock();
        try {
            CatalogState snapshot = store.snapshot();
            List<RepositoryView> repositories = snapshot.repositories().values().stream()
                    .filter(StoredRepository::registered)
                    .sorted((left, right) -> left.name().compareToIgnoreCase(right.name()))
                    .map(this::viewOf)
                    .toList();
            List<SearchRootView> roots = snapshot.searchRoots().stream()
                    .map(root -> new SearchRootView(root.id(), root.path(), root.createdAt()))
                    .toList();
            return new CodebaseCatalogView(platform(), gitRunner.isAvailable(store.dataDir()),
                    snapshot.activeRepositoryId(), repositories, roots);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public RepositoryView registerRepository(String absolutePath, String name, List<String> aliases) {
        lock.writeLock().lock();
        try {
            Path root = paths.requireRepositoryRoot(absolutePath);
            CatalogState snapshot = store.snapshot();
            StoredRepository existing = findByRealPath(snapshot.repositories().values(), root);
            Instant now = Instant.now();
            if (existing != null) {
                StoredRepository restored = new StoredRepository(existing.id(), root.toString(),
                        name == null || name.isBlank() ? existing.name() : normalizeName(name, root),
                        aliases == null ? existing.aliases() : normalizeAliases(aliases),
                        true, existing.createdAt(), now);
                if (!sameRecord(existing, restored)) {
                    store.saveRepository(restored);
                }
                if (store.snapshot().activeRepositoryId() == null) {
                    store.saveSettings(restored.id(), store.snapshot().searchRoots());
                }
                return viewOf(restored);
            }
            StoredRepository created = new StoredRepository(UUID.randomUUID(), root.toString(),
                    normalizeName(name, root), normalizeAliases(aliases), true, now, now);
            // 仓库资料先落盘，随后才可能成为 Active；崩溃后可安全恢复为无默认仓库。
            store.saveRepository(created);
            if (store.snapshot().activeRepositoryId() == null) {
                store.saveSettings(created.id(), store.snapshot().searchRoots());
            }
            return viewOf(created);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public RepositoryView updateRepository(UUID repositoryId, String name, List<String> aliases) {
        lock.writeLock().lock();
        try {
            StoredRepository current = requireRegistered(repositoryId);
            Path root = paths.resolveRegistered(current).root();
            StoredRepository updated = new StoredRepository(current.id(), current.path(),
                    name == null ? current.name() : normalizeName(name, root),
                    aliases == null ? current.aliases() : normalizeAliases(aliases),
                    true, current.createdAt(), Instant.now());
            store.saveRepository(updated);
            return viewOf(updated);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public CodebaseCatalogView unregisterRepository(UUID repositoryId) {
        lock.writeLock().lock();
        try {
            StoredRepository current = requireRegistered(repositoryId);
            CatalogState snapshot = store.snapshot();
            if (repositoryId.equals(snapshot.activeRepositoryId())) {
                // Active 先清空，避免中途崩溃后 settings 指向随后被标记为未注册的仓库。
                store.saveSettings(null, snapshot.searchRoots());
            }
            StoredRepository unregistered = new StoredRepository(current.id(), current.path(), current.name(),
                    current.aliases(), false, current.createdAt(), Instant.now());
            store.saveRepository(unregistered);
            return catalog();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public CodebaseCatalogView setActiveRepository(UUID repositoryId) {
        lock.writeLock().lock();
        try {
            if (repositoryId != null) {
                StoredRepository repository = requireRegistered(repositoryId);
                // Active 只接受当前可访问的工作树，避免 UI 选择一个失效默认值。
                paths.resolveRegistered(repository);
            }
            CatalogState snapshot = store.snapshot();
            store.saveSettings(repositoryId, snapshot.searchRoots());
            return catalog();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public SearchRootView addSearchRoot(String absolutePath) {
        lock.writeLock().lock();
        try {
            Path root = paths.requireSearchRoot(absolutePath);
            CatalogState snapshot = store.snapshot();
            for (StoredSearchRoot existing : snapshot.searchRoots()) {
                if (samePath(root, Path.of(existing.path()))) {
                    return new SearchRootView(existing.id(), existing.path(), existing.createdAt());
                }
            }
            StoredSearchRoot created = new StoredSearchRoot(UUID.randomUUID(), root.toString(), Instant.now());
            List<StoredSearchRoot> roots = new ArrayList<>(snapshot.searchRoots());
            roots.add(created);
            store.saveSettings(snapshot.activeRepositoryId(), roots);
            return new SearchRootView(created.id(), created.path(), created.createdAt());
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public CodebaseCatalogView removeSearchRoot(UUID searchRootId) {
        lock.writeLock().lock();
        try {
            CatalogState snapshot = store.snapshot();
            if (snapshot.searchRoots().stream().noneMatch(root -> root.id().equals(searchRootId))) {
                throw new CodebaseException(CodebaseErrorCode.REPOSITORY_NOT_FOUND, "Search Root 不存在");
            }
            List<StoredSearchRoot> roots = snapshot.searchRoots().stream()
                    .filter(root -> !root.id().equals(searchRootId))
                    .toList();
            store.saveSettings(snapshot.activeRepositoryId(), roots);
            return catalog();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Evidence 实现共享的重新解析 seam；调用方不能拿到物理路径字符串自行拼接。 */
    public RepositoryLocation resolveRegistered(UUID repositoryId) {
        lock.readLock().lock();
        try {
            return paths.resolveRegistered(requireRegistered(repositoryId));
        } finally {
            lock.readLock().unlock();
        }
    }

    private RepositoryView viewOf(StoredRepository repository) {
        GitObservation observation;
        try {
            RepositoryLocation location = paths.resolveRegistered(repository);
            observation = git.observe(location);
        } catch (CodebaseException ex) {
            return new RepositoryView(repository.id(), repository.path(), repository.name(), repository.aliases(),
                    repository.registered(), "UNAVAILABLE", null, null, false, false, false, false,
                    0, 0, 0, 0, ex.code().name(), repository.createdAt(), repository.updatedAt());
        }
        return new RepositoryView(repository.id(), repository.path(), repository.name(), repository.aliases(),
                repository.registered(), observation.status(), observation.branch(), observation.head(),
                observation.dirty(), observation.unborn(), observation.detached(), observation.shallow(),
                observation.stagedCount(), observation.unstagedCount(), observation.untrackedCount(),
                observation.sensitiveChangedCount(), observation.unavailableCode(), repository.createdAt(),
                repository.updatedAt());
    }

    private StoredRepository requireRegistered(UUID repositoryId) {
        if (repositoryId == null) {
            throw new CodebaseException(CodebaseErrorCode.REPOSITORY_NOT_FOUND, "仓库不存在");
        }
        StoredRepository repository = store.snapshot().repositories().get(repositoryId);
        if (repository == null || !repository.registered()) {
            throw new CodebaseException(CodebaseErrorCode.REPOSITORY_NOT_FOUND, "仓库不存在");
        }
        return repository;
    }

    private StoredRepository findByRealPath(Iterable<StoredRepository> repositories, Path root) {
        for (StoredRepository repository : repositories) {
            try {
                Path stored = Path.of(repository.path());
                if (samePath(root, stored)) {
                    return repository;
                }
            } catch (RuntimeException ignored) {
                // CatalogStore 已经会拒绝非法路径；这里不让一条失效记录阻止新仓库注册。
            }
        }
        return null;
    }

    private boolean samePath(Path first, Path second) {
        Path left = first.toAbsolutePath().normalize();
        Path right = second.toAbsolutePath().normalize();
        if (left.equals(right)) {
            return true;
        }
        try {
            return Files.exists(left) && Files.exists(right) && Files.isSameFile(left, right);
        } catch (IOException ex) {
            return false;
        }
    }

    private String normalizeName(String value, Path root) {
        Path fileName = root.getFileName();
        String fallback = fileName == null ? root.toString() : fileName.toString();
        String normalized = value == null || value.isBlank() ? fallback : value.trim();
        if (normalized.isBlank() || normalized.length() > 128) {
            throw new CodebaseException(CodebaseErrorCode.INVALID_QUERY, "仓库名称不合法");
        }
        return normalized;
    }

    private List<String> normalizeAliases(List<String> values) {
        if (values == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String normalized = value.trim();
            if (normalized.length() > 128) {
                throw new CodebaseException(CodebaseErrorCode.INVALID_QUERY, "仓库别名不合法");
            }
            if (seen.add(normalized.toLowerCase(Locale.ROOT))) {
                result.add(normalized);
            }
        }
        return List.copyOf(result);
    }

    private boolean sameRecord(StoredRepository left, StoredRepository right) {
        return Objects.equals(left.id(), right.id()) && Objects.equals(left.path(), right.path())
                && Objects.equals(left.name(), right.name()) && Objects.equals(left.aliases(), right.aliases())
                && left.registered() == right.registered() && Objects.equals(left.updatedAt(), right.updatedAt());
    }

    private PlatformView platform() {
        boolean windows = java.io.File.separatorChar == '\\';
        String os = System.getProperty("os.name", "unknown");
        return new PlatformView(os, windows ? "\\\\" : "/", windows,
                windows ? "D:\\project\\repo" : "/home/user/project/repo");
    }
}
