package com.yuyu.salmonmind.codebase.api;

import java.time.Instant;
import java.util.UUID;

/** 用户明确授权用于后续精确仓库发现的目录。添加它不会自动扫描或注册子目录。 */
public record SearchRootView(UUID id, String path, Instant createdAt) {
}
