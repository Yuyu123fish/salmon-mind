package com.yuyu.salmonmind.codebase.api;

import java.util.UUID;

/** Assistant Entry 成功追加后用于幂等发布 pending 调用链的最小确认。 */
public record CallChainConfirmation(
        UUID repositoryId,
        UUID callChainId,
        UUID answerEntryId
) {
}
