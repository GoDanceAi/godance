package ai.godance.storage;

/**
 * Provider-agnostic object storage. Implementations are S3-compatible:
 * MinIO (dev), DigitalOcean Spaces (DO prod), Alibaba OSS (Alicloud prod).
 * See {@link S3ObjectStorageClient}.
 */
public interface ObjectStorageClient {

    /**
     * Upload {@code data} under {@code key} with the given {@code contentType}.
     * Returns the canonical key (caller passes back to {@link #presignedUrl}).
     */
    String upload(String key, byte[] data, String contentType);

    /** Pre-signed GET URL with TTL from {@code godance.object-storage.presign-ttl}. */
    String presignedUrl(String key);

    /** Delete an object. Idempotent — no error if the key doesn't exist. */
    void delete(String key);
}
