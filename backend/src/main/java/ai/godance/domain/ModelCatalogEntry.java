package ai.godance.domain;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * Runtime catalog row — one per registered model. Money columns are
 * microcents (consistent with {@code ModelCatalog.ModelInfo} and the rest
 * of the credit ledger); zero is the sentinel for "field not applicable
 * to this modality" (e.g. {@code per_image_microcents} on a text model).
 *
 * <p>Read path: {@link ai.godance.gateway.ModelCatalog} loads {@code
 * status=ACTIVE} rows at startup (and on approval-driven refresh) into an
 * in-memory map. Write path: the V11 seed plus admin-approved proposals
 * via {@code ProposalApprovalService}.
 */
@Entity
@Table(name = "model_catalog")
public class ModelCatalogEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "model_name", nullable = false, unique = true)
    private String modelName;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false)
    private Provider provider;

    @Enumerated(EnumType.STRING)
    @Column(name = "modality", nullable = false)
    private Modality modality;

    @Column(name = "input_microcents_per_1k", nullable = false)
    private long inputMicrocentsPer1k;

    @Column(name = "output_microcents_per_1k", nullable = false)
    private long outputMicrocentsPer1k;

    @Column(name = "per_image_microcents", nullable = false)
    private long perImageMicrocents;

    @Column(name = "per_second_microcents", nullable = false)
    private long perSecondMicrocents;

    @Column(name = "default_max_output_tokens")
    private Integer defaultMaxOutputTokens;

    @Column(name = "markup_override")
    private Double markupOverride;

    @Column(name = "requires_async", nullable = false)
    private boolean requiresAsync;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private CatalogStatus status;

    @Column(name = "cost_source")
    private String costSource;

    @Column(name = "last_verified_at")
    private Instant lastVerifiedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public Provider getProvider() { return provider; }
    public void setProvider(Provider provider) { this.provider = provider; }

    public Modality getModality() { return modality; }
    public void setModality(Modality modality) { this.modality = modality; }

    public long getInputMicrocentsPer1k() { return inputMicrocentsPer1k; }
    public void setInputMicrocentsPer1k(long v) { this.inputMicrocentsPer1k = v; }

    public long getOutputMicrocentsPer1k() { return outputMicrocentsPer1k; }
    public void setOutputMicrocentsPer1k(long v) { this.outputMicrocentsPer1k = v; }

    public long getPerImageMicrocents() { return perImageMicrocents; }
    public void setPerImageMicrocents(long v) { this.perImageMicrocents = v; }

    public long getPerSecondMicrocents() { return perSecondMicrocents; }
    public void setPerSecondMicrocents(long v) { this.perSecondMicrocents = v; }

    public Integer getDefaultMaxOutputTokens() { return defaultMaxOutputTokens; }
    public void setDefaultMaxOutputTokens(Integer v) { this.defaultMaxOutputTokens = v; }

    public Double getMarkupOverride() { return markupOverride; }
    public void setMarkupOverride(Double v) { this.markupOverride = v; }

    public boolean isRequiresAsync() { return requiresAsync; }
    public void setRequiresAsync(boolean v) { this.requiresAsync = v; }

    public CatalogStatus getStatus() { return status; }
    public void setStatus(CatalogStatus status) { this.status = status; }

    public String getCostSource() { return costSource; }
    public void setCostSource(String v) { this.costSource = v; }

    public Instant getLastVerifiedAt() { return lastVerifiedAt; }
    public void setLastVerifiedAt(Instant v) { this.lastVerifiedAt = v; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ModelCatalogEntry other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
