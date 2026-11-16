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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * A user's own key for one provider (V40, round 14 brief 03). The key is
 * stored only encrypted, through {@code KeyVault}, with the user and the
 * provider as its context; {@code last4} is all of it that is ever shown.
 */
@Entity
@Table(name = "provider_credentials")
public class ProviderCredential {

    /** What the last check of the key found, as the API names it. */
    public static final String VALID = "valid";
    public static final String INVALID = "invalid";
    public static final String UNCHECKED = "unchecked";

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, updatable = false)
    private Provider provider;

    /** {@code v1:…}, from {@code KeyVault.encrypt}; never the key itself. */
    @Column(name = "key_encrypted", columnDefinition = "text", nullable = false)
    private String keyEncrypted;

    @Column(name = "last4", nullable = false)
    private String last4;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "fallback_to_platform", nullable = false)
    private boolean fallbackToPlatform;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** When the provider last said whether the key works: at saving, or when it refused the key. */
    @Column(name = "checked_at")
    private Instant checkedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public Provider getProvider() { return provider; }
    public void setProvider(Provider provider) { this.provider = provider; }

    public String getKeyEncrypted() { return keyEncrypted; }
    public void setKeyEncrypted(String keyEncrypted) { this.keyEncrypted = keyEncrypted; }

    public String getLast4() { return last4; }
    public void setLast4(String last4) { this.last4 = last4; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public boolean isFallbackToPlatform() { return fallbackToPlatform; }
    public void setFallbackToPlatform(boolean fallbackToPlatform) { this.fallbackToPlatform = fallbackToPlatform; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getCheckedAt() { return checkedAt; }
    public void setCheckedAt(Instant checkedAt) { this.checkedAt = checkedAt; }

    public Instant getUpdatedAt() { return updatedAt; }

    /** Never the key: an encrypted value or a last four is all this would print. */
    @Override
    public String toString() {
        return "ProviderCredential[" + provider + ", …" + last4 + ", " + status + "]";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProviderCredential other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
