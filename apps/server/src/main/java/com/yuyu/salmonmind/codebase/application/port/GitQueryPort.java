package com.yuyu.salmonmind.codebase.application.port;

import java.util.List;

/**
 * 面向应用编排的结构化 Git 查询合同。
 *
 * <p>查询实现可以更换 Git 进程细节，但必须继续返回只读观察、固定命令结果和已验证 commit。</p>
 */
public interface GitQueryPort {

    /** 读取 status 等只读事实并过滤敏感路径；不通过 Git 写入仓库。 */
    GitObservation observe(RepositoryLocation location);

    /** 在已解析工作树上执行一个固定只读 Git 查询。 */
    GitProcessPort.Result run(RepositoryLocation location, List<String> arguments);

    /** 将用户 ref 解析为完整 commit ID，失败时拒绝后续历史查询。 */
    String resolveCommit(RepositoryLocation location, String ref);
}
