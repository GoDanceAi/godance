package ai.godance.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * An image its owner uploaded to use in a generation (V31). Private to
 * {@code userId}; stored with its metadata stripped, under a key that
 * cannot be guessed.
 *
 * <p>{@code expiresAt} is when an unused file is deleted, and null while a
 * job uses it. {@code deletedAt} is set once the object is gone; the row
 * then stays only while a job still records the file.
 */
@Entity
@Table(name = "input_files")
public class InputFile {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "storage_key", nullable = false, updatable = false)
    private String storageKey;

    @Column(name = "content_type", nullable = false, updatable = false)
    private String contentType;

    @Column(name = "bytes", nullable = false, updatable = false)
    private long bytes;

    @Column(name = "width", nullable = false, updatable = false)
    private int width;

    @Column(name = "height", nullable = false, updatable = false)
    private int height;

    /** What the person check said at upload; null when no check ran. */
    @Column(name = "contains_person", updatable = false)
    private Boolean containsPerson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Read-only here: only {@code InputFileRepository}'s updates write it. */
    @Column(name = "expires_at", updatable = false)
    private Instant expiresAt;

    /** Read-only here: only {@code InputFileRepository}'s updates write it. */
    @Column(name = "deleted_at", insertable = false, updatable = false)
    private Instant deletedAt;

    protected InputFile() { }

    public InputFile(UUID id, UUID userId, String storageKey, String contentType, long bytes,
                     int width, int height, Boolean containsPerson, Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.userId = userId;
        this.storageKey = storageKey;
        this.contentType = contentType;
        this.bytes = bytes;
        this.width = width;
        this.height = height;
        this.containsPerson = containsPerson;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getStorageKey() { return storageKey; }
    public String getContentType() { return contentType; }
    public long getBytes() { return bytes; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public Boolean getContainsPerson() { return containsPerson; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getDeletedAt() { return deletedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof InputFile other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
