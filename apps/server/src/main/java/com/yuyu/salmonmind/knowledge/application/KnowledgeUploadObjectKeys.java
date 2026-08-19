package com.yuyu.salmonmind.knowledge.application;

import java.util.UUID;

/** Upload 对象专属且不可配置的版本化前缀；它与 Redis key prefix 有意分离。 */
public final class KnowledgeUploadObjectKeys {

    public static final String PART_ROOT = "knowledge/upload-parts/v1/";
    public static final String FINAL_ROOT = "knowledge/upload-finals/v1/";

    private KnowledgeUploadObjectKeys() {
    }

    public static String partPrefix(String bucket, UUID workspaceId, UUID sessionId) {
        return PART_ROOT + bucket + "/" + workspaceId + "/" + sessionId + "/";
    }

    public static String finalKey(String bucket, UUID workspaceId, UUID sessionId) {
        return FINAL_ROOT + bucket + "/" + workspaceId + "/" + sessionId + ".bin";
    }
}
