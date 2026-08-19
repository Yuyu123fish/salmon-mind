package com.yuyu.salmonmind.codebase.infrastructure.git;

import com.yuyu.salmonmind.codebase.api.CodebaseErrorCode;
import com.yuyu.salmonmind.codebase.api.CodebaseException;
import com.yuyu.salmonmind.codebase.application.port.GitObservation;
import com.yuyu.salmonmind.codebase.application.port.GitProcessPort;
import com.yuyu.salmonmind.codebase.application.port.GitQueryPort;
import com.yuyu.salmonmind.codebase.application.port.RepositoryLocation;
import com.yuyu.salmonmind.codebase.domain.SensitiveFilePolicy;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Git 查询的低层只读事实适配器；高层接口负责参数和输出预算。 */
@Component
public final class GitRepositoryQuery implements GitQueryPort {

    private final GitProcessPort runner;
    private final SensitiveFilePolicy policy;

    public GitRepositoryQuery(GitProcessPort runner, SensitiveFilePolicy policy) {
        this.runner = runner;
        this.policy = policy;
    }

    @Override
    public GitObservation observe(RepositoryLocation location) {
        GitProcessPort.Result result = runner.run(location.root(),
                List.of("status", "--porcelain=v2", "--branch", "--untracked-files=all", "-z"));
        if (!result.succeeded() || result.stdoutOverflow()) {
            throw new CodebaseException(CodebaseErrorCode.GIT_QUERY_FAILED,
                    "Git 状态查询失败");
        }
        String branch = null;
        String head = null;
        boolean unborn = false;
        boolean detached = false;
        int staged = 0;
        int unstaged = 0;
        int untracked = 0;
        int sensitive = 0;
        boolean dirty = false;
        List<GitObservation.StatusItem> items = new ArrayList<>();
        String[] records = result.stdout().split("\\u0000", -1);
        for (int index = 0; index < records.length; index++) {
            String record = records[index];
            if (record.isEmpty()) {
                continue;
            }
            if (record.startsWith("# branch.head ")) {
                branch = record.substring("# branch.head ".length());
                detached = "(detached)".equals(branch);
                if (detached) {
                    branch = null;
                }
                continue;
            }
            if (record.startsWith("# branch.oid ")) {
                head = record.substring("# branch.oid ".length());
                if ("(initial)".equals(head)) {
                    head = null;
                    unborn = true;
                }
                continue;
            }
            if (record.startsWith("# ")) {
                continue;
            }
            String path = statusPath(record);
            if (path == null || path.isBlank()) {
                continue;
            }
            char x = record.length() > 2 ? record.charAt(2) : '.';
            char y = record.length() > 3 ? record.charAt(3) : '.';
            String kind;
            if (record.startsWith("? ")) {
                untracked++;
                kind = "UNTRACKED";
            } else {
                if (x != '.') {
                    staged++;
                }
                if (y != '.') {
                    unstaged++;
                }
                kind = x != '.' && y != '.' ? "STAGED_AND_UNSTAGED"
                        : x != '.' ? "STAGED" : "UNSTAGED";
            }
            dirty = true;
            if (policy.isDenied(location.root(), path, null)) {
                sensitive++;
            } else if (items.size() < 500) {
                items.add(new GitObservation.StatusItem(path, kind));
            }
            if (record.startsWith("2 ") && index + 1 < records.length) {
                index++;
            }
        }
        boolean shallow = false;
        GitProcessPort.Result shallowResult = runner.run(location.root(),
                List.of("rev-parse", "--is-shallow-repository"));
        if (shallowResult.succeeded()) {
            shallow = "true".equalsIgnoreCase(shallowResult.stdout().trim());
        }
        return new GitObservation("AVAILABLE", branch, head, dirty, unborn, detached, shallow,
                staged, unstaged, untracked, sensitive, items, null);
    }

    @Override
    public GitProcessPort.Result run(RepositoryLocation location, List<String> arguments) {
        return runner.run(location.root(), arguments);
    }

    @Override
    public String resolveCommit(RepositoryLocation location, String ref) {
        if (ref == null || ref.isBlank() || ref.length() > 512 || ref.indexOf('\0') >= 0) {
            throw new CodebaseException(CodebaseErrorCode.INVALID_QUERY, "Git ref 参数不合法");
        }
        GitProcessPort.Result result = run(location,
                List.of("rev-parse", "--verify", "--end-of-options", ref + "^{commit}"));
        if (!result.succeeded() || result.stdout().isBlank()) {
            throw new CodebaseException(CodebaseErrorCode.GIT_QUERY_FAILED,
                    "Git ref 无法解析");
        }
        String commit = result.stdout().trim().split("\\R", 2)[0];
        if (!commit.matches("[0-9a-fA-F]{40,64}")) {
            throw new CodebaseException(CodebaseErrorCode.GIT_QUERY_FAILED,
                    "Git ref 无法解析");
        }
        return commit;
    }

    private String statusPath(String record) {
        if (record.startsWith("? ") || record.startsWith("! ")) {
            return record.substring(2);
        }
        int tab = record.indexOf('\t');
        if (tab < 0 || tab + 1 >= record.length()) {
            return null;
        }
        return record.substring(tab + 1);
    }
}
