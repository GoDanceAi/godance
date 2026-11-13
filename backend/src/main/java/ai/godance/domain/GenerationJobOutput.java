package ai.godance.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * One stored file of a generation job (V23): {@code images/<jobId>/<i>.<ext>}
 * or {@code videos/<jobId>/<i>.<ext>}. The link is not stored; it is signed
 * from {@link #getStorageKey()} whenever the job is read.
 */
@Entity
@Table(name = "generation_job_outputs")
public class GenerationJobOutput {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "job_id", nullable = false, updatable = false)
    private UUID jobId;

    @Column(name = "output_index", nullable = false, updatable = false)
    private int outputIndex;

    @Column(name = "storage_key", nullable = false, updatable = false)
    private String storageKey;

    @Column(name = "content_type", nullable = false, updatable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "revised_prompt", columnDefinition = "text", updatable = false)
    private String revisedPrompt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected GenerationJobOutput() { }

    public GenerationJobOutput(UUID jobId, int outputIndex, String storageKey, String contentType,
                               long sizeBytes, String revisedPrompt) {
        this.jobId = jobId;
        this.outputIndex = outputIndex;
        this.storageKey = storageKey;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.revisedPrompt = revisedPrompt;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public UUID getJobId() { return jobId; }
    public int getOutputIndex() { return outputIndex; }
    public String getStorageKey() { return storageKey; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getRevisedPrompt() { return revisedPrompt; }
    public Instant getCreatedAt() { return createdAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GenerationJobOutput other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
