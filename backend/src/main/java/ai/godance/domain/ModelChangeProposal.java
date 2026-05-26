package ai.godance.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Refresh-job staging lane. Every detected change — price moves, new
 * versions of an existing family, brand-new model ids, and pathological
 * observations — lands here first. A human approves (or rejects) before
 * any change reaches the live {@code model_catalog}.
 *
 * <p>Sanity failures are persisted with {@code status=ANOMALY} so they
 * never silently disappear and so the daily digest can surface them.
 */
@Entity
@Table(name = "model_change_proposal")
public class ModelChangeProposal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false)
    private ProposalType type;

    @Column(name = "model_name", nullable = false, updatable = false)
    private String modelName;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", updatable = false)
    private Provider provider;

    @Enumerated(EnumType.STRING)
    @Column(name = "modality", updatable = false)
    private Modality modality;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> payload;

    @Column(name = "source", updatable = false)
    private String source;

    @Column(name = "source_url", updatable = false)
    private String sourceUrl;

    @Column(name = "detected_at", nullable = false, updatable = false)
    private Instant detectedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ProposalStatus status;

    @Column(name = "reviewed_by")
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public ProposalType getType() { return type; }
    public void setType(ProposalType type) { this.type = type; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public Provider getProvider() { return provider; }
    public void setProvider(Provider provider) { this.provider = provider; }

    public Modality getModality() { return modality; }
    public void setModality(Modality modality) { this.modality = modality; }

    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }

    public Instant getDetectedAt() { return detectedAt; }
    public void setDetectedAt(Instant detectedAt) { this.detectedAt = detectedAt; }

    public ProposalStatus getStatus() { return status; }
    public void setStatus(ProposalStatus status) { this.status = status; }

    public String getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(String reviewedBy) { this.reviewedBy = reviewedBy; }

    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ModelChangeProposal other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
