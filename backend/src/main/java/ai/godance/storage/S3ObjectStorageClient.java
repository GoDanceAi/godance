package ai.godance.storage;

import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ai.godance.config.ObjectStorageProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
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
}
