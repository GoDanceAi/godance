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

/**
 * Append-only ledger of all credit balance changes.
 * Reads compute balance via SUM(delta_cents). {@link User#getCreditBalanceCents()}
 * is a denormalized cache updated in the same transaction as the insert.
 */
@Entity
@Table(name = "credit_ledger")
public class CreditLedger {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "delta_cents", nullable = false, updatable = false)
    private long deltaCents;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, updatable = false)
    private CreditSource source;

    @Column(name = "source_ref", updatable = false)
    private String sourceRef;

    @Column(name = "idempotency_key", nullable = false, unique = true, updatable = false)
    private String idempotencyKey;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "note", columnDefinition = "text", updatable = false)
    private String note;

    @PrePersist
    void onCreate() {
        if (occurredAt == null) occurredAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public long getDeltaCents() { return deltaCents; }
    public void setDeltaCents(long deltaCents) { this.deltaCents = deltaCents; }

    public CreditSource getSource() { return source; }
    public void setSource(CreditSource source) { this.source = source; }

    public String getSourceRef() { return sourceRef; }
    public void setSourceRef(String sourceRef) { this.sourceRef = sourceRef; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CreditLedger other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
