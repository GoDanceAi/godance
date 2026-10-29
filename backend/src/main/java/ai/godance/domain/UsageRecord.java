package ai.godance.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "usage_records")
public class UsageRecord {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "api_key_id")
    private UUID apiKeyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "modality", nullable = false)
    private Modality modality;

    @Column(name = "model_name", nullable = false)
    private String modelName;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false)
    private Provider provider;

    @Column(name = "input_tokens", nullable = false)
    private long inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private long outputTokens;

    @Column(name = "cost_cents", nullable = false)
    private long costCents;

    @Column(name = "provider_cost_cents")
    private Long providerCostCents;

    /** Exact marked-up cost in microcents; {@code costCents} is what the carry billed. */
    @Column(name = "cost_microcents")
    private Long costMicrocents;

    /** Exact provider list cost in microcents, no markup. */
    @Column(name = "provider_cost_microcents")
    private Long providerCostMicrocents;

    @Enumerated(EnumType.STRING)
    @Column(name = "region", nullable = false)
    private Region region;

    @Column(name = "cache_hit", nullable = false)
    private boolean cacheHit;

    @Column(name = "fallback_used", nullable = false)
    private boolean fallbackUsed;

    @Column(name = "request_id")
    private String requestId;

    /** Upstream round-trip in milliseconds; {@code null} for uninstrumented rows. */
    @Column(name = "latency_ms")
    private Integer latencyMs;

    /** Upstream call outcome; {@code null} for uninstrumented rows (treated as OK). */
    @Enumerated(EnumType.STRING)
    @Column(name = "outcome")
    private CallOutcome outcome;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    @PrePersist
    void onCreate() {
        if (recordedAt == null) recordedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public UUID getApiKeyId() { return apiKeyId; }
    public void setApiKeyId(UUID apiKeyId) { this.apiKeyId = apiKeyId; }

    public Modality getModality() { return modality; }
    public void setModality(Modality modality) { this.modality = modality; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public Provider getProvider() { return provider; }
    public void setProvider(Provider provider) { this.provider = provider; }

    public long getInputTokens() { return inputTokens; }
    public void setInputTokens(long inputTokens) { this.inputTokens = inputTokens; }

    public long getOutputTokens() { return outputTokens; }
    public void setOutputTokens(long outputTokens) { this.outputTokens = outputTokens; }

    public long getCostCents() { return costCents; }
    public void setCostCents(long costCents) { this.costCents = costCents; }

    public Long getProviderCostCents() { return providerCostCents; }
    public void setProviderCostCents(Long providerCostCents) { this.providerCostCents = providerCostCents; }

    public Long getCostMicrocents() { return costMicrocents; }
    public void setCostMicrocents(Long costMicrocents) { this.costMicrocents = costMicrocents; }

    public Long getProviderCostMicrocents() { return providerCostMicrocents; }
    public void setProviderCostMicrocents(Long providerCostMicrocents) { this.providerCostMicrocents = providerCostMicrocents; }

    public Region getRegion() { return region; }
    public void setRegion(Region region) { this.region = region; }

    public boolean isCacheHit() { return cacheHit; }
    public void setCacheHit(boolean cacheHit) { this.cacheHit = cacheHit; }

    public boolean isFallbackUsed() { return fallbackUsed; }
    public void setFallbackUsed(boolean fallbackUsed) { this.fallbackUsed = fallbackUsed; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public Integer getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Integer latencyMs) { this.latencyMs = latencyMs; }

    public CallOutcome getOutcome() { return outcome; }
    public void setOutcome(CallOutcome outcome) { this.outcome = outcome; }

    public Instant getRecordedAt() { return recordedAt; }
    public void setRecordedAt(Instant recordedAt) { this.recordedAt = recordedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UsageRecord other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
