package com.yuyu.salmonmind.codebase.api;

import java.util.List;
import java.util.UUID;

/** Web 侧调用链查询和用户管理接口。 */
public interface CallChainQueryService {

    List<CallChainSummary> list(UUID repositoryId);

    CallChainDetail detail(UUID repositoryId, UUID callChainId);

    CallChainDetail rename(UUID repositoryId, UUID callChainId, String name);

    CallChainDetail delete(UUID repositoryId, UUID callChainId);
}
