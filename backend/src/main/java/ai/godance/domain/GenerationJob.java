package ai.godance.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "generation_jobs")
public class GenerationJob {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "modality", nullable = false, updatable = false)
    private Modality modality;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, updatable = false)
    private Provider provider;

    @Column(name = "model", nullable = false, updatable = false)
    private String model;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "prompt_json", columnDefinition = "jsonb", nullable = false, updatable = false)
    private Map<String, Object> prompt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private JobStatus status;

    @Column(name = "output_url")
    private String outputUrl;

    @Column(name = "estimated_cost_cents")
    private Long estimatedCostCents;

    @Column(name = "actual_cost_cents")
    private Long actualCostCents;

    @Column(name = "webhook_url")
    private String webhookUrl;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /** Ledger request id of the pre-debit; the refund key is {@code refund-<requestId>}. Null on pre-V16 rows. */
    @Column(name = "request_id")
    private String requestId;

    /** Latest time a live worker can still hold the job; an open job past it is swept. Null on pre-V16 rows. */
    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    /**
     * When the job's media was deleted and its prompt and URLs redacted (V20);
     * null until then. Read-only here: only {@code GenerationJobRepository.redactPurged} writes it.
     */
    @Column(name = "purged_at", insertable = false, updatable = false)
    private Instant purgedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public Modality getModality() { return modality; }
    public void setModality(Modality modality) { this.modality = modality; }

    public Provider getProvider() { return provider; }
    public void setProvider(Provider provider) { this.provider = provider; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public Map<String, Object> getPrompt() { return prompt; }
    public void setPrompt(Map<String, Object> prompt) { this.prompt = prompt; }

    public JobStatus getStatus() { return status; }
    public void setStatus(JobStatus status) { this.status = status; }

    public String getOutputUrl() { return outputUrl; }
    public void setOutputUrl(String outputUrl) { this.outputUrl = outputUrl; }

    public Long getEstimatedCostCents() { return estimatedCostCents; }
    public void setEstimatedCostCents(Long estimatedCostCents) { this.estimatedCostCents = estimatedCostCents; }

    public Long getActualCostCents() { return actualCostCents; }
    public void setActualCostCents(Long actualCostCents) { this.actualCostCents = actualCostCents; }

    public String getWebhookUrl() { return webhookUrl; }
    public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    public void setLeaseExpiresAt(Instant leaseExpiresAt) { this.leaseExpiresAt = leaseExpiresAt; }

    public Instant getPurgedAt() { return purgedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GenerationJob other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
