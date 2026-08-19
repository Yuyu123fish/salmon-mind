package com.yuyu.salmonmind.knowledge.infrastructure.s3;

import com.yuyu.salmonmind.knowledge.api.KnowledgeException;
import com.yuyu.salmonmind.knowledge.application.port.ObjectStoragePort;
import com.yuyu.salmonmind.knowledge.application.port.ResumableUploadStoragePort;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** RustFS/S3 原件 Adapter：只保存不可变原件，所有派生文本仍由 Worker 重建。 */
@Component
class S3ObjectStorage implements ObjectStoragePort, ResumableUploadStoragePort {

    private static final Logger log = LoggerFactory.getLogger(S3ObjectStorage.class);

    private final String endpoint;
    private final String accessKey;
    private final String secretKey;
    private final String bucket;
    private final String region;
    private final Duration apiCallTimeout;
    private final Duration apiAttemptTimeout;

    private volatile S3Client client;
    private volatile boolean bucketReady;

    S3ObjectStorage(
            @Value("${salmon.knowledge.content-store.endpoint:}") String endpoint,
            @Value("${salmon.knowledge.content-store.access-key:}") String accessKey,
            @Value("${salmon.knowledge.content-store.secret-key:}") String secretKey,
            @Value("${salmon.knowledge.content-store.bucket:salmon-knowledge}") String bucket,
            @Value("${salmon.knowledge.content-store.region:us-east-1}") String region,
            @Value("${salmon.knowledge.upload.api-call-timeout:15s}") Duration apiCallTimeout,
            @Value("${salmon.knowledge.upload.api-attempt-timeout:10s}") Duration apiAttemptTimeout
    ) {
        this.endpoint = endpoint;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.bucket = bucket;
        this.region = region;
        this.apiCallTimeout = apiCallTimeout;
        this.apiAttemptTimeout = apiAttemptTimeout;
    }

    @Override
    public void put(Path file, String objectKey, String mediaType) {
        try {
            ensureBucket();
            client().putObject(PutObjectRequest.builder()
                            .bucket(bucket).key(objectKey).contentType(mediaType).build(),
                    RequestBody.fromFile(file));
        } catch (KnowledgeException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("Knowledge 原件写入失败，objectKey={}", objectKey, ex);
            throw new KnowledgeException(KnowledgeException.Code.OBJECT_STORAGE_UNAVAILABLE, "原件存储不可用", ex);
        }
    }

    @Override
    public void download(String objectKey, Path target) {
        try {
            ensureBucket();
            // AWS SDK 的 toFile 要求目标不存在；调用方通常先创建临时文件，Adapter 负责覆盖语义。
            Files.deleteIfExists(target);
            client().getObject(GetObjectRequest.builder().bucket(bucket).key(objectKey).build(),
                    ResponseTransformer.toFile(target));
        } catch (IOException ex) {
            log.warn("Knowledge 原件目标文件不可写，objectKey={}", objectKey, ex);
            throw new KnowledgeException(KnowledgeException.Code.OBJECT_STORAGE_UNAVAILABLE, "原件目标文件不可写", ex);
        } catch (RuntimeException ex) {
            log.warn("Knowledge 原件读取失败，objectKey={}", objectKey, ex);
            throw new KnowledgeException(KnowledgeException.Code.OBJECT_STORAGE_UNAVAILABLE, "原件读取失败", ex);
        }
    }

    @Override
    public void putObject(Path file, String objectKey, String mediaType) {
        put(file, objectKey, mediaType);
    }

    @Override
    public void downloadObject(String objectKey, Path target) {
        download(objectKey, target);
    }

    @Override
    public ResumableUploadStoragePort.ObjectHead headObject(String objectKey) {
        try {
            ensureBucket();
            var response = client().headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey).build());
            return new ResumableUploadStoragePort.ObjectHead(objectKey, response.contentLength(), response.contentType(),
                    response.lastModified() == null ? Instant.EPOCH : response.lastModified());
        } catch (RuntimeException ex) {
            throw new KnowledgeException(KnowledgeException.Code.OBJECT_STORAGE_UNAVAILABLE, "上传对象读取失败", ex);
        }
    }

    @Override
    public ResumableUploadStoragePort.ObjectPage listObjects(String prefix, String continuationToken, int maxKeys) {
        try {
            ensureBucket();
            ListObjectsV2Request.Builder request = ListObjectsV2Request.builder()
                    .bucket(bucket).prefix(prefix).maxKeys(Math.max(1, Math.min(1_000, maxKeys)));
            if (StringUtils.hasText(continuationToken)) request.continuationToken(continuationToken);
            ListObjectsV2Response response = client().listObjectsV2(request.build());
            List<ResumableUploadStoragePort.ObjectHead> objects = response.contents().stream()
                    .map(this::toObjectHead).toList();
            return new ResumableUploadStoragePort.ObjectPage(objects, response.nextContinuationToken(),
                    Boolean.TRUE.equals(response.isTruncated()));
        } catch (RuntimeException ex) {
            throw new KnowledgeException(KnowledgeException.Code.OBJECT_STORAGE_UNAVAILABLE, "上传对象列表读取失败", ex);
        }
    }

    @Override
    public void deleteObject(String objectKey) {
        if (!StringUtils.hasText(objectKey)) return;
        try {
            ensureBucket();
            client().deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build());
        } catch (RuntimeException ex) {
            throw new KnowledgeException(KnowledgeException.Code.OBJECT_STORAGE_UNAVAILABLE, "上传对象清理失败", ex);
        }
    }

    private ResumableUploadStoragePort.ObjectHead toObjectHead(S3Object object) {
        return new ResumableUploadStoragePort.ObjectHead(object.key(), object.size(), null,
                object.lastModified() == null ? Instant.EPOCH : object.lastModified());
    }

    @Override
    public void deleteBestEffort(String objectKey) {
        if (!StringUtils.hasText(endpoint) || !StringUtils.hasText(objectKey)) {
            return;
        }
        try {
            client().deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build());
        } catch (RuntimeException ex) {
            // 双写失败时只尽力删除本次已知对象，不能扩大清理范围；清理失败必须可诊断。
            log.warn("Knowledge 孤儿原件清理失败，objectKey={}", objectKey, ex);
        }
    }

    @Override
    public void deleteStrict(String objectKey) {
        if (!StringUtils.hasText(endpoint) || !StringUtils.hasText(accessKey)
                || !StringUtils.hasText(secretKey) || !StringUtils.hasText(objectKey)) {
            throw new KnowledgeException(KnowledgeException.Code.OBJECT_STORAGE_UNAVAILABLE,
                    "原件存储未配置");
        }
        try {
            S3Client current = client();
            try {
                String versioning = current.getBucketVersioning(GetBucketVersioningRequest.builder()
                        .bucket(bucket).build()).statusAsString();
                if (StringUtils.hasText(versioning) && !"OFF".equalsIgnoreCase(versioning)) {
                    // 当前 Revision 没有保存 versionId；DeleteObject 在版本化 Bucket 中不能证明原件已消失。
                    throw new KnowledgeException(KnowledgeException.Code.OBJECT_STORAGE_UNAVAILABLE,
                            "原件 Bucket 启用了不受支持的 Versioning");
                }
            } catch (S3Exception ex) {
                if (ex.statusCode() == 404) {
                    // Bucket 已不存在，目标对象不可能仍可读取；按幂等删除收束。
                    return;
                }
                throw ex;
            }

            current.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build());
            try {
                current.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey).build());
            } catch (S3Exception ex) {
                if (ex.statusCode() == 404) {
                    return;
                }
                throw ex;
            }
            throw new KnowledgeException(KnowledgeException.Code.OBJECT_STORAGE_UNAVAILABLE,
                    "原件删除后仍可读取");
        } catch (KnowledgeException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("Knowledge 原件严格删除失败，objectKey={}", objectKey, ex);
            throw new KnowledgeException(KnowledgeException.Code.OBJECT_STORAGE_UNAVAILABLE,
                    "原件删除失败", ex);
        }
    }

    @PreDestroy
    void close() {
        S3Client current = client;
        if (current != null) {
            current.close();
        }
    }

    private synchronized void ensureBucket() {
        if (bucketReady) {
            return;
        }
        if (!StringUtils.hasText(endpoint) || !StringUtils.hasText(accessKey) || !StringUtils.hasText(secretKey)) {
            throw new KnowledgeException(KnowledgeException.Code.OBJECT_STORAGE_UNAVAILABLE, "原件存储未配置");
        }
        try {
            client().headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (S3Exception ex) {
            if (ex.statusCode() != 404) {
                throw ex;
            }
            client().createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        }
        bucketReady = true;
    }

    private synchronized S3Client client() {
        if (client == null) {
            if (!StringUtils.hasText(endpoint)) {
                throw new KnowledgeException(KnowledgeException.Code.OBJECT_STORAGE_UNAVAILABLE, "原件存储未配置");
            }
            client = S3Client.builder()
                    .endpointOverride(URI.create(endpoint))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(accessKey, secretKey)))
                    .region(Region.of(region))
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                    .overrideConfiguration(ClientOverrideConfiguration.builder()
                            .apiCallTimeout(apiCallTimeout).apiCallAttemptTimeout(apiAttemptTimeout).build())
                    .httpClientBuilder(UrlConnectionHttpClient.builder())
                    .build();
        }
        return client;
    }
}
