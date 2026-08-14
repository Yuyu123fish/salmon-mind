package com.yuyu.salmonmind.workspace.api;

import java.time.Instant;
import java.util.UUID;

/** 本地安装中唯一工作空间的稳定结果。 */
public record Workspace(UUID id, String name, Instant createdAt) {
}
