package ai.godance.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * S3-compatible object storage. Provider-agnostic: MinIO locally,
 * DigitalOcean Spaces in DO region, Alibaba OSS in Alicloud region.
 *
 * <p>{@code presignTtl} controls the lifetime of generated download URLs
 * (image outputs, video outputs). 7 days is the AWS SDK maximum for
 * SigV4-presigned GETs; longer needs to come from a CDN or public bucket.
 *
 * <p>{@code region} is required by the AWS SDK signer; for MinIO any
 * placeholder works (e.g. {@code us-east-1}).
 */
@ConfigurationProperties(prefix = "godance.object-storage")
public record ObjectStorageProperties(
        String endpoint,
        String region,
        String accessKey,
        String secretKey,
        String bucket,
        Duration presignTtl,
        boolean pathStyleAccess     // true for MinIO, false for AWS S3 production
) {
    public ObjectStorageProperties {
        if (endpoint == null || endpoint.isBlank())
            throw new IllegalStateException("godance.object-storage.endpoint must be set");
        if (region == null || region.isBlank())
            throw new IllegalStateException("godance.object-storage.region must be set");
        if (accessKey == null) throw new IllegalStateException("godance.object-storage.access-key must be configured");
        if (secretKey == null) throw new IllegalStateException("godance.object-storage.secret-key must be configured");
        if (bucket == null || bucket.isBlank())
            throw new IllegalStateException("godance.object-storage.bucket must be set");
        if (presignTtl == null || presignTtl.isNegative() || presignTtl.isZero())
            throw new IllegalStateException("godance.object-storage.presign-ttl must be positive");
        if (presignTtl.toDays() > 7)
            throw new IllegalStateException("godance.object-storage.presign-ttl exceeds SigV4 maximum of 7 days");
    }
}
