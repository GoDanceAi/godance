package ai.godance.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * A request's estimate held against its caller's spending caps until the
 * request's usage row exists or it is refunded (spec risk R-10). See
 * {@code SpendingLimitService#reserve}.
 */
@Entity
@Table(name = "spend_holds")
public class SpendHold {

    @Id
    @Column(name = "request_id", nullable = false, updatable = false)
    private String requestId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "api_key_id", updatable = false)
    private UUID apiKeyId;

    @Column(name = "cents", nullable = false, updatable = false)
    private long cents;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public UUID getApiKeyId() { return apiKeyId; }
    public void setApiKeyId(UUID apiKeyId) { this.apiKeyId = apiKeyId; }

    public long getCents() { return cents; }
    public void setCents(long cents) { this.cents = cents; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
