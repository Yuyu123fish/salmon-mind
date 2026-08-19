package com.yuyu.salmonmind.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.core.ResponseInputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 固定 RustFS beta.12 与当前 AWS SDK 普通对象接口的兼容门禁。
 *
 * <p>测试只创建随机 Bucket 内的精确对象；不调用 Bucket 清理，也不触碰 Compose 共享容器。</p>
 */
@Testcontainers
class KnowledgeChunkObjectCompatibilityIntegrationTest {

    private static final String ACCESS_KEY = "salmonmind";
    private static final String SECRET_KEY = "salmonmind-test-secret";
    private static final Duration API_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration ATTEMPT_TIMEOUT = Duration.ofMillis(500);

    @Container
    static final GenericContainer<?> RUSTFS = new GenericContainer<>("rustfs/rustfs:1.0.0-beta.12")
            .withCommand("/data")
            .withEnv("RUSTFS_ADDRESS", "0.0.0.0:9000")
            .withEnv("RUSTFS_CONSOLE_ADDRESS", "0.0.0.0:9001")
            .withEnv("RUSTFS_CONSOLE_ENABLE", "false")
            .withEnv("RUSTFS_ACCESS_KEY", ACCESS_KEY)
            .withEnv("RUSTFS_SECRET_KEY", SECRET_KEY)
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/health").forPort(9000).forStatusCode(200));

    @Test
    void fixedRustFsSupportsChunkObjectsAssemblyPaginationAndPreciseCleanup() throws Exception {
        String bucket = "salmon-chunk-gate-" + UUID.randomUUID().toString().replace("-", "");
        String rootPrefix = "knowledge/upload-parts/v1/gate/" + UUID.randomUUID() + "/";
        String partsPrefix = rootPrefix + "parts/";
        String finalKey = "knowledge/upload-finals/v1/gate/" + UUID.randomUUID() + ".bin";
        Set<String> createdKeys = new LinkedHashSet<>();

        try (S3Client s3 = client(rustFsEndpoint())) {
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            try {
                Map<Integer, PartFixture> parts = new LinkedHashMap<>();
                parts.put(1, fixture(partsPrefix + "001.bin", "第一段\n".repeat(700).getBytes(StandardCharsets.UTF_8)));
                parts.put(2, fixture(partsPrefix + "002.bin", "第二段\n".repeat(900).getBytes(StandardCharsets.UTF_8)));
                parts.put(3, fixture(partsPrefix + "003.bin", "第三段\n".repeat(500).getBytes(StandardCharsets.UTF_8)));
                String adjacentKey = rootPrefix + "not-a-part.bin";

                for (PartFixture part : parts.values()) {
                    put(s3, bucket, part.key(), part.bytes());
                    createdKeys.add(part.key());
                }
                // 同一确定性 key 的重试覆盖只影响该 key，后续 Receipt 仍以服务端已校验内容为准。
                PartFixture replacement = fixture(parts.get(2).key(),
                        "第二段替换内容\n".repeat(850).getBytes(StandardCharsets.UTF_8));
                put(s3, bucket, replacement.key(), replacement.bytes());
                parts.put(2, replacement);
                put(s3, bucket, adjacentKey, "相邻前缀对象".getBytes(StandardCharsets.UTF_8));
                createdKeys.add(adjacentKey);

                for (PartFixture part : parts.values()) {
                    var head = s3.headObject(HeadObjectRequest.builder()
                            .bucket(bucket).key(part.key()).build());
                    assertThat(head.contentLength()).isEqualTo((long) part.bytes().length);
                    assertThat(head.contentType()).isEqualTo("application/octet-stream");
                    assertThat(head.lastModified()).isNotNull();
                    assertThat(readBytes(s3, bucket, part.key())).containsExactly(part.bytes());
                    assertThat(readAndHash(s3, bucket, part.key()).sha256())
                            .isEqualTo(part.sha256());
                }

                List<S3Object> listedParts = listAllPages(s3, bucket, partsPrefix);
                assertThat(listedParts).hasSize(parts.size());
                assertThat(listedParts).extracting(S3Object::key)
                        .containsExactlyInAnyOrderElementsOf(parts.values().stream()
                                .map(PartFixture::key).toList());
                assertThat(listedParts).allSatisfy(object -> {
                    assertThat(object.key()).startsWith(partsPrefix);
                    assertThat(object.lastModified()).isNotNull();
                    assertThat(object.lastModified()).isBeforeOrEqualTo(Instant.now());
                });

                Path merged = Files.createTempFile("salmon-chunk-gate-", ".bin");
                try {
                    MessageDigest fullDigest = sha256Digest();
                    long totalBytes = 0;
                    try (OutputStream output = Files.newOutputStream(merged)) {
                        for (PartFixture part : parts.values()) {
                            ReadResult result = streamInto(s3, bucket, part.key(), output, fullDigest);
                            assertThat(result.bytes()).isEqualTo(part.bytes().length);
                            assertThat(result.sha256()).isEqualTo(part.sha256());
                            totalBytes += result.bytes();
                        }
                    }
                    assertThat(Files.size(merged)).isEqualTo(totalBytes);
                    assertThat(sha256(merged)).isEqualTo(sha256(Files.readAllBytes(merged)));

                    put(s3, bucket, finalKey, merged);
                    createdKeys.add(finalKey);
                    var finalHead = s3.headObject(HeadObjectRequest.builder()
                            .bucket(bucket).key(finalKey).build());
                    assertThat(finalHead.contentLength()).isEqualTo(Files.size(merged));
                    assertThat(finalHead.contentType()).isEqualTo("application/octet-stream");
                    assertThat(readAndHash(s3, bucket, finalKey).sha256()).isEqualTo(sha256(merged));
                    assertThat(readBytes(s3, bucket, finalKey))
                            .containsExactly(Files.readAllBytes(merged));
                } finally {
                    Files.deleteIfExists(merged);
                }

                assertAgeRuleUsesTrustedObjectTimestamp(listedParts, Clock.systemUTC());
            } finally {
                cleanupExactObjects(s3, bucket, createdKeys);
            }
        }
    }

    @Test
    void ordinaryObjectClientAppliesBoundedApiAndAttemptTimeouts() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch requestReceived = new CountDownLatch(1);
        CountDownLatch releaseRequest = new CountDownLatch(1);
        server.createContext("/", exchange -> {
            requestReceived.countDown();
            try {
                releaseRequest.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try (S3Client s3 = client("http://127.0.0.1:" + server.getAddress().getPort())) {
            Instant started = Instant.now();
            assertThatThrownBy(() -> s3.headObject(HeadObjectRequest.builder()
                    .bucket("timeout-bucket").key("timeout-object").build()))
                    .isInstanceOf(SdkClientException.class);
            assertThat(requestReceived.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofSeconds(5));
        } finally {
            releaseRequest.countDown();
            server.stop(0);
        }
    }

    private S3Client client(String endpoint) {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .region(Region.US_EAST_1)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(API_TIMEOUT)
                        .apiCallAttemptTimeout(ATTEMPT_TIMEOUT)
                        .build())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    private String rustFsEndpoint() {
        return "http://" + RUSTFS.getHost() + ":" + RUSTFS.getMappedPort(9000);
    }

    private PartFixture fixture(String key, byte[] bytes) {
        return new PartFixture(key, bytes, sha256(bytes));
    }

    private void put(S3Client s3, String bucket, String key, byte[] bytes) {
        s3.putObject(PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType("application/octet-stream")
                        .contentLength((long) bytes.length)
                        .build(),
                RequestBody.fromBytes(bytes));
    }

    private void put(S3Client s3, String bucket, String key, Path file) {
        s3.putObject(PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType("application/octet-stream")
                        .contentLength(file.toFile().length())
                        .build(),
                RequestBody.fromFile(file));
    }

    private List<S3Object> listAllPages(S3Client s3, String bucket, String prefix) {
        List<S3Object> result = new ArrayList<>();
        Set<String> seenTokens = new HashSet<>();
        String continuationToken = null;
        int pages = 0;
        while (true) {
            ListObjectsV2Request.Builder builder = ListObjectsV2Request.builder()
                    .bucket(bucket)
                    .prefix(prefix)
                    .maxKeys(1);
            if (continuationToken != null) {
                builder.continuationToken(continuationToken);
            }
            ListObjectsV2Response response = s3.listObjectsV2(builder.build());
            result.addAll(response.contents());
            pages++;
            assertThat(pages).as("ListObjectsV2 分页必须有界").isLessThanOrEqualTo(20);
            if (!Boolean.TRUE.equals(response.isTruncated())) {
                break;
            }
            assertThat(response.nextContinuationToken()).isNotBlank();
            continuationToken = response.nextContinuationToken();
            assertThat(seenTokens.add(continuationToken))
                    .as("continuation token 不得重复").isTrue();
        }
        assertThat(pages).as("小 maxKeys 必须真实产生多页").isGreaterThan(1);
        return result;
    }

    private void assertAgeRuleUsesTrustedObjectTimestamp(List<S3Object> objects, Clock clock) {
        Instant now = clock.instant();
        Duration maxSessionLifetime = Duration.ofMinutes(30);
        Duration orphanGrace = Duration.ofMinutes(5);
        assertThat(objects).allSatisfy(object -> assertThat(object.lastModified()).isBeforeOrEqualTo(now));
        assertThat(isOrphanEligible(now.minus(maxSessionLifetime).minus(orphanGrace).minusSeconds(1),
                now, maxSessionLifetime, orphanGrace)).isTrue();
        assertThat(isOrphanEligible(now.minus(maxSessionLifetime).minus(orphanGrace),
                now, maxSessionLifetime, orphanGrace)).isFalse();
    }

    private boolean isOrphanEligible(
            Instant lastModified,
            Instant now,
            Duration maxSessionLifetime,
            Duration orphanGrace
    ) {
        return lastModified.isBefore(now.minus(maxSessionLifetime).minus(orphanGrace));
    }

    private ReadResult streamInto(
            S3Client s3,
            String bucket,
            String key,
            OutputStream output,
            MessageDigest fullDigest
    ) throws IOException {
        MessageDigest partDigest = sha256Digest();
        long bytes = 0;
        try (ResponseInputStream<GetObjectResponse> input = s3.getObject(
                GetObjectRequest.builder().bucket(bucket).key(key).build())) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                partDigest.update(buffer, 0, read);
                fullDigest.update(buffer, 0, read);
                bytes += read;
            }
        }
        return new ReadResult(bytes, hex(partDigest.digest()));
    }

    private ReadResult readAndHash(S3Client s3, String bucket, String key) throws IOException {
        MessageDigest digest = sha256Digest();
        long bytes = 0;
        try (InputStream input = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
                bytes += read;
            }
        }
        return new ReadResult(bytes, hex(digest.digest()));
    }

    private byte[] readBytes(S3Client s3, String bucket, String key) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream input = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())) {
            input.transferTo(output);
        }
        return output.toByteArray();
    }

    private void cleanupExactObjects(S3Client s3, String bucket, Set<String> keys) {
        for (String key : keys) {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
            assertMissing(s3, bucket, key);
        }
    }

    private void assertMissing(S3Client s3, String bucket, String key) {
        assertThatThrownBy(() -> s3.headObject(HeadObjectRequest.builder()
                .bucket(bucket).key(key).build()))
                .isInstanceOf(S3Exception.class)
                .satisfies(error -> assertThat(((S3Exception) error).statusCode()).isEqualTo(404));
        assertThatThrownBy(() -> s3.getObject(GetObjectRequest.builder()
                .bucket(bucket).key(key).build()))
                .isInstanceOf(S3Exception.class)
                .satisfies(error -> assertThat(((S3Exception) error).statusCode()).isEqualTo(404));
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JDK 必须提供 SHA-256", ex);
        }
    }

    private String sha256(byte[] bytes) {
        MessageDigest digest = sha256Digest();
        digest.update(bytes);
        return hex(digest.digest());
    }

    private String sha256(Path file) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return hex(digest.digest());
    }

    private String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }

    private record PartFixture(String key, byte[] bytes, String sha256) {
    }

    private record ReadResult(long bytes, String sha256) {
    }
}
