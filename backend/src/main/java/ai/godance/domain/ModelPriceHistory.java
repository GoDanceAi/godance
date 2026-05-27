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
import jakarta.persistence.Table;

/**
 * Append-only observed-price time series. Every refresh-job observation
 * writes a row (even when the value hasn't moved), so the question "did
 * Opus 4.7 go up or down last week" is always answerable from this table
 * alone.
 *
 * <p>{@code modelName} is FK-style (no enforced FK) so observations of
 * not-yet-discovered models and post-rename rows survive deletions on
 * {@link ModelCatalogEntry}.
 */
@Entity
@Table(name = "model_price_history")
public class ModelPriceHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "model_name", nullable = false, updatable = false)
    private String modelName;

    @Enumerated(EnumType.STRING)
    @Column(name = "field", nullable = false, updatable = false)
    private PriceField field;

    @Column(name = "old_value", updatable = false)
    private Long oldValue;

    @Column(name = "new_value", updatable = false)
    private Long newValue;

    @Column(name = "source", updatable = false)
    private String source;

    @Column(name = "source_url", updatable = false)
    private String sourceUrl;

    @Column(name = "observed_at", nullable = false, updatable = false)
    private Instant observedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public PriceField getField() { return field; }
    public void setField(PriceField field) { this.field = field; }

    public Long getOldValue() { return oldValue; }
    public void setOldValue(Long v) { this.oldValue = v; }

    public Long getNewValue() { return newValue; }
    public void setNewValue(Long v) { this.newValue = v; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }

    public Instant getObservedAt() { return observedAt; }
    public void setObservedAt(Instant observedAt) { this.observedAt = observedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ModelPriceHistory other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
