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

    /**
     * The bytes stored under {@code key}. A failure throws, a missing object
     * included. For small objects only: the whole object is read into memory.
     */
    byte[] download(String key);

    /**
     * The object stored under {@code key}, as a stream the caller reads and
     * closes. For objects of any size: nothing is held in memory beyond
     * what the caller reads. A failure throws, a missing object included.
     */
    java.io.InputStream open(String key);

    /** Pre-signed GET URL with TTL from {@code godance.object-storage.presign-ttl}. */
    String presignedUrl(String key);

    /**
     * Pre-signed GET URL that lasts {@code ttl}. For objects whose links
     * should be short-lived, such as input files.
     */
    String presignedUrl(String key, java.time.Duration ttl);

    /**
     * Copies the object under {@code sourceKey} to {@code targetKey} within
     * the bucket, on the storage side: nothing passes through this server,
     * so it suits video as well as images. The copy is independent of its
     * source; deleting either leaves the other. A failure throws, a missing
     * source included. Returns {@code targetKey}.
     */
    String copy(String sourceKey, String targetKey);

    /** Delete an object. Idempotent — no error if the key doesn't exist. */
    void delete(String key);

    /**
     * Delete every object whose key starts with {@code prefix} and return how
     * many there were; 0 when there are none. Unlike {@link #delete}, a
     * failure throws, so a caller never records a deletion that did not
     * happen. A blank prefix is refused: it would match the whole bucket.
     */
    int deletePrefix(String prefix);

    /** An object found by {@link #list}: its key and size in bytes. */
    record StoredObject(String key, long sizeBytes) { }

    /**
     * Every object whose key starts with {@code prefix}, in key order; empty
     * when there are none. A failure throws. A blank prefix is refused, as
     * for {@link #deletePrefix}.
     */
    java.util.List<StoredObject> list(String prefix);
}
