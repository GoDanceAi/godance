package ai.godance.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * One example output of a published recipe (V39): an output of one of the
 * jobs its author chose. {@code storageKey} is null until a moderator
 * approves the recipe, which copies the output; the copy is what the public
 * routes serve. {@code sourceJobId} never leaves the server.
 */
@Entity
@Table(name = "published_recipe_examples")
public class PublishedRecipeExample {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "published_recipe_id", nullable = false, updatable = false)
    private UUID publishedRecipeId;

    /** 1, 2, 3… in the order the public routes show them. */
    @Column(name = "position", nullable = false, updatable = false)
    private int position;

    @Column(name = "source_job_id")
    private UUID sourceJobId;

    @Column(name = "source_output_index", nullable = false, updatable = false)
    private int sourceOutputIndex;

    @Column(name = "storage_key")
    private String storageKey;

    @Column(name = "content_type")
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }

    public UUID getPublishedRecipeId() { return publishedRecipeId; }
    public void setPublishedRecipeId(UUID publishedRecipeId) { this.publishedRecipeId = publishedRecipeId; }

    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }

    public UUID getSourceJobId() { return sourceJobId; }
    public void setSourceJobId(UUID sourceJobId) { this.sourceJobId = sourceJobId; }

    public int getSourceOutputIndex() { return sourceOutputIndex; }
    public void setSourceOutputIndex(int sourceOutputIndex) { this.sourceOutputIndex = sourceOutputIndex; }

    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String storageKey) { this.storageKey = storageKey; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }

    public Instant getCreatedAt() { return createdAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PublishedRecipeExample other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
