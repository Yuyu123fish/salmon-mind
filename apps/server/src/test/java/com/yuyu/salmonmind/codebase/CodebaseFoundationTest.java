package com.yuyu.salmonmind.codebase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.salmonmind.codebase.api.CodebaseErrorCode;
import com.yuyu.salmonmind.codebase.api.CodebaseException;
import com.yuyu.salmonmind.codebase.api.CodebaseCatalogView;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService;
import com.yuyu.salmonmind.codebase.api.RepositoryResolution;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.GitBlameResult;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.GitDiffResult;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.GitLogResult;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.GitShowResult;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.GitStatusResult;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.GlobResult;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.GrepResult;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.ListDirectoryResult;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.ReadFileResult;
import com.yuyu.salmonmind.codebase.application.RepositoryCatalogService;
import com.yuyu.salmonmind.codebase.application.RepositoryEvidenceApplicationService;
import com.yuyu.salmonmind.codebase.infrastructure.filesystem.CatalogStore;
import com.yuyu.salmonmind.codebase.infrastructure.filesystem.RepositoryPathResolver;
import com.yuyu.salmonmind.codebase.domain.SensitiveFilePolicy;
import com.yuyu.salmonmind.codebase.infrastructure.git.GitProcessRunner;
import com.yuyu.salmonmind.codebase.infrastructure.git.GitRepositoryQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 临时真实 Git 仓库 Gate：只写测试自己的目录，验证 catalog、Evidence 与 Git 零写入边界。 */
class CodebaseFoundationTest {

    @TempDir
    Path temporaryDirectory;

    private Path repository;
    private Path catalogDirectory;
    private RepositoryCatalogService catalog;
    private RepositoryEvidenceService evidence;

    @BeforeEach
    void setUp() throws Exception {
        repository = temporaryDirectory.resolve("sample-repository");
        catalogDirectory = temporaryDirectory.resolve("repository-understanding");
        Files.createDirectories(repository.resolve("src"));
        write(repository.resolve(".gitignore"), ".env\nignored.txt\n");
        write(repository.resolve(".env.example"), "MODEL_KEY=replace-me\n");
        write(repository.resolve("src/Main.java"), "class Main {\n  void needle() {}\n}\n");
        write(repository.resolve("README.md"), "temporary repository\n");
        write(repository.resolve(".env"), "MODEL_KEY=real-secret\n");
        write(repository.resolve("config/credentials.json"), "{\"token\":\"real-secret\"}\n");
        write(repository.resolve("ignored.txt"), "ignored but readable\n");
        write(repository.resolve("untracked.txt"), "ordinary untracked\n");
        runGit("init", "-q");
        runGit("config", "user.name", "SalmonMind Test");
        runGit("config", "user.email", "salmonmind-test@example.invalid");
        runGit("add", ".gitignore", ".env.example", "src/Main.java", "README.md");
        runGit("commit", "-qm", "initial repository");
        Files.writeString(repository.resolve("src/Main.java"),
                "class Main {\n  void needle() { /* changed */ }\n}\n", StandardCharsets.UTF_8);

        GitProcessRunner runner = new GitProcessRunner("git", Duration.ofSeconds(10));
        CatalogStore store = new CatalogStore(new ObjectMapper(), catalogDirectory.toString());
        RepositoryPathResolver pathResolver = new RepositoryPathResolver(runner);
        SensitiveFilePolicy policy = new SensitiveFilePolicy(List.of(catalogDirectory));
        GitRepositoryQuery git = new GitRepositoryQuery(runner, policy);
        catalog = new RepositoryCatalogService(store, pathResolver, git, runner);
        evidence = new RepositoryEvidenceApplicationService(catalog, pathResolver, policy, git);
        catalog.registerRepository(repository.toString(), null, List.of());
    }

    @Test
    void catalogKeepsRealRepositoryIdentityAcrossDuplicatePathsCancelAndRestart() throws Exception {
        var first = catalog.catalog().repositories().getFirst();
        var duplicate = catalog.registerRepository(repository.resolve("src").toString().replace('\\', '/'), null, List.of());

        assertThat(duplicate.id()).isEqualTo(first.id());
        assertThat(catalog.catalog().repositories()).hasSize(1);

        var searchRoot = catalog.addSearchRoot(temporaryDirectory.toString());
        assertThat(catalog.addSearchRoot(temporaryDirectory.toString()).id()).isEqualTo(searchRoot.id());
        assertThat(catalog.catalog().searchRoots()).hasSize(1);

        CodebaseCatalogView afterCancel = catalog.unregisterRepository(first.id());
        assertThat(afterCancel.activeRepositoryId()).isNull();
        assertThat(afterCancel.repositories()).isEmpty();
        var restored = catalog.registerRepository(repository.toString(), null, List.of());
        assertThat(restored.id()).isEqualTo(first.id());

        CatalogStore restartedStore = new CatalogStore(new ObjectMapper(), catalogDirectory.toString());
        RepositoryCatalogService restarted = new RepositoryCatalogService(
                restartedStore,
                new RepositoryPathResolver(new GitProcessRunner("git", Duration.ofSeconds(10))),
                new GitRepositoryQuery(new GitProcessRunner("git", Duration.ofSeconds(10)),
                        new SensitiveFilePolicy(List.of(catalogDirectory))),
                new GitProcessRunner("git", Duration.ofSeconds(10)));
        assertThat(restarted.catalog().activeRepositoryId()).isEqualTo(first.id());
        assertThat(restarted.catalog().repositories()).singleElement().extracting("path")
                .isEqualTo(repository.toRealPath().toString());
    }

    @Test
    void resolvesExactReferencesWithoutChangingActiveOrFallingBack() throws Exception {
        var active = catalog.catalog().repositories().getFirst();
        catalog.updateRepository(active.id(), "primary", List.of("main-alias"));
        UUID activeId = active.id();

        assertThat(catalog.resolveRepository(null).status()).isEqualTo(RepositoryResolution.Status.RESOLVED);
        assertThat(catalog.resolveRepository("MAIN-ALIAS").repository().id()).isEqualTo(activeId);
        assertThat(catalog.resolveRepository(repository.toString().replace('\\', '/')).repository().id())
                .isEqualTo(activeId);
        assertThat(catalog.catalog().activeRepositoryId()).isEqualTo(activeId);

        Path discovered = createGitRepository("discovered-repository");
        catalog.addSearchRoot(temporaryDirectory.toString());
        RepositoryResolution discoveredResolution = catalog.resolveRepository(discovered.getFileName().toString());
        assertThat(discoveredResolution.status()).isEqualTo(RepositoryResolution.Status.RESOLVED);
        assertThat(discoveredResolution.repository().path()).isEqualTo(discovered.toRealPath().toString());
        assertThat(catalog.catalog().activeRepositoryId()).isEqualTo(activeId);

        Path second = createGitRepository("second-repository");
        catalog.registerRepository(second.toString(), "primary", List.of());
        RepositoryResolution ambiguous = catalog.resolveRepository("primary");
        assertThat(ambiguous.status()).isEqualTo(RepositoryResolution.Status.SELECTION_REQUIRED);
        assertThat(ambiguous.candidates()).hasSize(2);
        assertThat(catalog.catalog().activeRepositoryId()).isEqualTo(activeId);

        RepositoryResolution missing = catalog.resolveRepository("does-not-exist");
        assertThat(missing.status()).isEqualTo(RepositoryResolution.Status.NOT_FOUND);
        assertThat(missing.reason()).isEqualTo("REFERENCE_NOT_FOUND");
        assertThat(missing.repository()).isNull();
        assertThat(catalog.catalog().activeRepositoryId()).isEqualTo(activeId);
    }
    @Test
    void rejectsRelativePathsAndCorruptedCatalogWithoutFallingBack() throws Exception {
        assertThatThrownBy(() -> catalog.registerRepository("relative/repository", null, List.of()))
                .isInstanceOf(CodebaseException.class)
                .extracting("code")
                .isEqualTo(CodebaseErrorCode.INVALID_ABSOLUTE_PATH);

        Path settings = catalogDirectory.resolve("settings.json");
        write(settings, "{\"formatVersion\":999,\"activeRepositoryId\":null,\"searchRoots\":[]}");
        assertThatThrownBy(() -> new CatalogStore(new ObjectMapper(), catalogDirectory.toString()))
                .isInstanceOf(CodebaseException.class)
                .extracting("code")
                .isEqualTo(CodebaseErrorCode.CODEBASE_DATA_CORRUPTED);
    }

    @Test
    void evidenceHonorsIgnoreSensitiveAndReadOnlyGitBoundaries() throws Exception {
        var id = catalog.catalog().activeRepositoryId();

        ListDirectoryResult listing = evidence.listDirectory(
                new RepositoryEvidenceService.ListDirectoryQuery(id, "", 500));
        assertThat(listing.entries()).extracting("path").contains("src", "README.md", "untracked.txt");
        assertThat(listing.entries()).extracting("path").doesNotContain(".git", ".env");

        GlobResult glob = evidence.glob(new RepositoryEvidenceService.GlobQuery(id, "**/*.java", 200));
        assertThat(glob.paths()).containsExactly("src/Main.java");

        GrepResult grep = evidence.grep(new RepositoryEvidenceService.GrepQuery(
                id, "needle", true, false, 1, 200));
        assertThat(grep.matches()).singleElement().satisfies(match -> {
            assertThat(match.path()).isEqualTo("src/Main.java");
            assertThat(match.line()).isEqualTo(2);
        });

        ReadFileResult ignored = evidence.readFile(new RepositoryEvidenceService.ReadFileQuery(
                id, "ignored.txt", 1, 20));
        assertThat(ignored.ignored()).isTrue();
        assertThat(ignored.content()).contains("ignored but readable");
        assertThatThrownBy(() -> evidence.readFile(new RepositoryEvidenceService.ReadFileQuery(
                id, ".env", 1, 20))).isInstanceOf(CodebaseException.class)
                .extracting("code").isEqualTo(CodebaseErrorCode.SENSITIVE_FILE_DENIED);
        assertThatThrownBy(() -> evidence.readFile(new RepositoryEvidenceService.ReadFileQuery(
                id, "../outside.txt", 1, 20))).isInstanceOf(CodebaseException.class)
                .extracting("code").isEqualTo(CodebaseErrorCode.PATH_OUTSIDE_REPOSITORY);

        GitStatusResult status = evidence.gitStatus(new RepositoryEvidenceService.GitStatusQuery(id));
        assertThat(status.metadata().dirty()).isTrue();
        assertThat(status.sensitiveChangedCount()).isGreaterThanOrEqualTo(1);
        assertThat(status.entries()).extracting("path").doesNotContain(".env");

        String head = runGit("rev-parse", "HEAD").trim();
        GitDiffResult diff = evidence.gitDiff(new RepositoryEvidenceService.GitDiffQuery(
                id, RepositoryEvidenceService.DiffScope.WORKTREE, null, null, List.of("src/Main.java")));
        assertThat(diff.patch()).contains("changed");
        runGit("add", "src/Main.java");
        Map<String, String> before = repositoryFingerprintByFile(repository);
        GitDiffResult staged = evidence.gitDiff(new RepositoryEvidenceService.GitDiffQuery(
                id, RepositoryEvidenceService.DiffScope.STAGED, null, null, List.of("src/Main.java")));
        assertThat(staged.patch()).contains("changed");
        GitDiffResult sameCommit = evidence.gitDiff(new RepositoryEvidenceService.GitDiffQuery(
                id, RepositoryEvidenceService.DiffScope.COMMITS, head, head, List.of("src/Main.java")));
        assertThat(sameCommit.patch()).isEmpty();
        assertThatThrownBy(() -> evidence.gitLog(new RepositoryEvidenceService.GitLogQuery(
                id, "src/Main.java", "HEAD^{commit};config", 30, 0)))
                .isInstanceOf(CodebaseException.class)
                .extracting("code").isEqualTo(CodebaseErrorCode.GIT_QUERY_FAILED);
        assertThatThrownBy(() -> new GitProcessRunner("git", Duration.ofSeconds(10))
                .run(repository, List.of("config", "--local", "unsafe.key", "unsafe.value")))
                .isInstanceOf(CodebaseException.class)
                .extracting("code").isEqualTo(CodebaseErrorCode.INVALID_QUERY);
        GitLogResult log = evidence.gitLog(new RepositoryEvidenceService.GitLogQuery(
                id, "src/Main.java", null, 30, 0));
        assertThat(log.entries()).isNotEmpty();
        GitShowResult show = evidence.gitShow(new RepositoryEvidenceService.GitShowQuery(id, head, null));
        assertThat(show.commit()).isEqualTo(head);
        GitBlameResult blame = evidence.gitBlame(new RepositoryEvidenceService.GitBlameQuery(
                id, "src/Main.java", null, 1, 20));
        assertThat(blame.lines()).isNotEmpty();
        assertThatThrownBy(() -> evidence.gitDiff(new RepositoryEvidenceService.GitDiffQuery(
                id, RepositoryEvidenceService.DiffScope.WORKTREE, null, null, List.of(".env"))))
                .isInstanceOf(CodebaseException.class)
                .extracting("code").isEqualTo(CodebaseErrorCode.SENSITIVE_FILE_DENIED);

        assertThat(repositoryFingerprintByFile(repository)).containsExactlyInAnyOrderEntriesOf(before);
    }

    private Path createGitRepository(String name) throws IOException, InterruptedException {
        Path root = temporaryDirectory.resolve(name);
        Files.createDirectories(root);
        write(root.resolve("README.md"), name + "\n");
        runGitAt(root, "init", "-q");
        runGitAt(root, "config", "user.name", "SalmonMind Test");
        runGitAt(root, "config", "user.email", "salmonmind-test@example.invalid");
        runGitAt(root, "add", "README.md");
        runGitAt(root, "commit", "-qm", "initial repository");
        return root;
    }

    private void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private String runGit(String... arguments) throws IOException, InterruptedException {
        return runGitAt(repository, arguments);
    }

    private String runGitAt(Path root, String... arguments) throws IOException, InterruptedException {
        List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).directory(root.toFile()).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(error).isZero();
        return output;
    }

    private Map<String, String> repositoryFingerprintByFile(Path root) throws Exception {
        Map<String, String> result = new java.util.TreeMap<>();
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).sorted()
                    .forEach(path -> {
                        try {
                            result.put(root.relativize(path).toString(),
                                    java.util.HexFormat.of().formatHex(
                                            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
                        } catch (IOException ex) {
                            throw new IllegalStateException(ex);
                        } catch (java.security.NoSuchAlgorithmException ex) {
                            throw new AssertionError(ex);
                        }
                    });
        }
        return result;
    }
}
