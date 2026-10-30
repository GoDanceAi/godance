package ai.godance.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * An account's two-factor state (V21). {@code enabledAt} is null while
 * enrolment waits for its first code; 2FA is on once it is set.
 */
@Entity
@Table(name = "user_two_factor")
public class UserTwoFactor {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** The TOTP secret as {@link ai.godance.security.KeyVault} stores it. */
    @Column(name = "secret_encrypted", nullable = false)
    private String secretEncrypted;

    @Column(name = "enabled_at")
    private Instant enabledAt;

    /** The highest accepted TOTP time step; only a later one is accepted next. */
    @Column(name = "last_used_step", nullable = false)
    private long lastUsedStep = 0L;

    /** SHA-256 hashes of the unused recovery codes. Replace the list to change it. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "recovery_code_hashes", columnDefinition = "jsonb", nullable = false)
    private List<String> recoveryCodeHashes = List.of();

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts = 0;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

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

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public String getSecretEncrypted() { return secretEncrypted; }
    public void setSecretEncrypted(String secretEncrypted) { this.secretEncrypted = secretEncrypted; }

    public Instant getEnabledAt() { return enabledAt; }
    public void setEnabledAt(Instant enabledAt) { this.enabledAt = enabledAt; }

    public long getLastUsedStep() { return lastUsedStep; }
    public void setLastUsedStep(long lastUsedStep) { this.lastUsedStep = lastUsedStep; }

    public List<String> getRecoveryCodeHashes() { return recoveryCodeHashes; }
    public void setRecoveryCodeHashes(List<String> hashes) { this.recoveryCodeHashes = hashes; }

    public int getFailedAttempts() { return failedAttempts; }
    public void setFailedAttempts(int failedAttempts) { this.failedAttempts = failedAttempts; }

    public Instant getLockedUntil() { return lockedUntil; }
    public void setLockedUntil(Instant lockedUntil) { this.lockedUntil = lockedUntil; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
