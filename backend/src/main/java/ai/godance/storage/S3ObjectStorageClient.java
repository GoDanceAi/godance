package ai.godance.storage;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ai.godance.config.ObjectStorageProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Error;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * AWS-SDK-v2 backed implementation. Works against MinIO and DO Spaces
 * by overriding the endpoint and (for MinIO) flipping
 * {@code path-style-access=true}.
 *
 * <p>Auto-creates the configured bucket on startup if missing — keeps
 * dev/test setup zero-friction. In prod the bucket is expected to
 * already exist (lifecycle policies, public access, CDN config, etc.
 * live there); the create call is a no-op if the bucket exists.
 */
@Component
public class S3ObjectStorageClient implements ObjectStorageClient {

    private static final Logger log = LoggerFactory.getLogger(S3ObjectStorageClient.class);

    private final ObjectStorageProperties props;
    private S3Client s3;
    private S3Presigner presigner;

    public S3ObjectStorageClient(ObjectStorageProperties props) {
        this.props = props;
    }

    @PostConstruct
    void start() {
        AwsBasicCredentials creds = AwsBasicCredentials.create(props.accessKey(), props.secretKey());
        URI endpoint = URI.create(props.endpoint());
        S3Configuration s3cfg = S3Configuration.builder()
                .pathStyleAccessEnabled(props.pathStyleAccess())
                .build();

        this.s3 = S3Client.builder()
                .endpointOverride(endpoint)
                .region(Region.of(props.region()))
                .credentialsProvider(StaticCredentialsProvider.create(creds))
                .serviceConfiguration(s3cfg)
                .overrideConfiguration(o -> o.addExecutionInterceptor(new DeleteObjectsContentMd5()))
                .build();

        this.presigner = S3Presigner.builder()
                .endpointOverride(endpoint)
                .region(Region.of(props.region()))
                .credentialsProvider(StaticCredentialsProvider.create(creds))
                .serviceConfiguration(s3cfg)
                .build();

        ensureBucketExists();
        log.info("S3ObjectStorageClient ready: endpoint={} bucket={} pathStyle={}",
                props.endpoint(), props.bucket(), props.pathStyleAccess());
    }

    @PreDestroy
    void stop() {
        if (presigner != null) presigner.close();
        if (s3 != null) s3.close();
    }

    private void ensureBucketExists() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(props.bucket()).build());
        } catch (NoSuchBucketException nope) {
            log.info("Creating bucket {} (did not exist)", props.bucket());
            s3.createBucket(CreateBucketRequest.builder().bucket(props.bucket()).build());
        } catch (S3Exception e) {
            // 404 from HeadBucket arrives as a generic S3Exception on some servers.
            if (e.statusCode() == 404) {
                log.info("Creating bucket {} (HeadBucket 404)", props.bucket());
                s3.createBucket(CreateBucketRequest.builder().bucket(props.bucket()).build());
            } else {
                throw e;
            }
        }
    }

    @Override
    public String upload(String key, byte[] data, String contentType) {
        try {
            s3.putObject(
                    PutObjectRequest.builder()
                            .bucket(props.bucket())
                            .key(key)
                            .contentType(contentType)
                            .contentLength((long) data.length)
                            .build(),
                    RequestBody.fromBytes(data));
            return key;
        } catch (SdkException e) {
            throw new IllegalStateException(
                    "object storage upload failed for key=" + key + ": " + e.getMessage(), e);
        }
    }

    @Override
    public String presignedUrl(String key) {
        GetObjectRequest get = GetObjectRequest.builder()
                .bucket(props.bucket())
                .key(key)
                .build();
        GetObjectPresignRequest req = GetObjectPresignRequest.builder()
                .signatureDuration(props.presignTtl())
                .getObjectRequest(get)
                .build();
        return presigner.presignGetObject(req).url().toString();
    }

    @Override
    public void delete(String key) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(props.bucket())
                    .key(key)
                    .build());
        } catch (SdkException e) {
            log.warn("object storage delete failed for key={}: {}", key, e.getMessage());
        }
    }

    /**
     * Lists the prefix with ListObjectsV2 and deletes each page (at most
     * 1,000 keys, the DeleteObjects limit) with one DeleteObjects call.
     */
    @Override
    public int deletePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("refusing to delete by an empty prefix");
        }
        int deleted = 0;
        String continuation = null;
        try {
            do {
                ListObjectsV2Response page = s3.listObjectsV2(ListObjectsV2Request.builder()
                        .bucket(props.bucket())
                        .prefix(prefix)
                        .continuationToken(continuation)
                        .build());
                List<ObjectIdentifier> keys = page.contents().stream()
                        .map(o -> ObjectIdentifier.builder().key(o.key()).build())
                        .toList();
                if (!keys.isEmpty()) {
                    DeleteObjectsResponse result = s3.deleteObjects(DeleteObjectsRequest.builder()
                            .bucket(props.bucket())
                            .delete(Delete.builder().objects(keys).quiet(true).build())
                            .build());
                    if (result.hasErrors() && !result.errors().isEmpty()) {
                        S3Error first = result.errors().get(0);
                        throw new IllegalStateException("object storage could not delete "
                                + result.errors().size() + " object(s) under prefix=" + prefix
                                + ", first key=" + first.key() + ": " + first.code() + " " + first.message());
                    }
                    deleted += keys.size();
                }
                continuation = Boolean.TRUE.equals(page.isTruncated()) ? page.nextContinuationToken() : null;
            } while (continuation != null);
        } catch (SdkException e) {
            throw new IllegalStateException(
                    "object storage prefix delete failed for prefix=" + prefix + ": " + e.getMessage(), e);
        }
        return deleted;
    }

    /**
     * Puts the {@code Content-MD5} header back on DeleteObjects requests.
     * Since 2.30 the AWS SDK sends a CRC32 checksum there instead, and MinIO
     * refuses a DeleteObjects without Content-MD5 ("Missing required header
     * for this request: Content-Md5"); other S3-compatible stores may too.
     * Every other request is left as the SDK built it.
     */
    static final class DeleteObjectsContentMd5 implements ExecutionInterceptor {
        @Override
        public SdkHttpRequest modifyHttpRequest(Context.ModifyHttpRequest context,
                                                ExecutionAttributes executionAttributes) {
            SdkHttpRequest request = context.httpRequest();
            if (!(context.request() instanceof DeleteObjectsRequest)
                    || request.firstMatchingHeader("Content-MD5").isPresent()
                    || context.requestBody().isEmpty()) {
                return request;
            }
            try (InputStream body = context.requestBody().get().contentStreamProvider().newStream()) {
                byte[] md5 = MessageDigest.getInstance("MD5").digest(body.readAllBytes());
                return request.toBuilder()
                        .putHeader("Content-MD5", Base64.getEncoder().encodeToString(md5))
                        .build();
            } catch (IOException | NoSuchAlgorithmException e) {
                throw new IllegalStateException("could not compute Content-MD5 for DeleteObjects", e);
            }
        }
    }
}
