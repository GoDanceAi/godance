package ai.godance.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A user's referral code (V26), made the first time they ask for it. Rows
 * are written only by {@code ReferralCodeRepository.insertIfAbsent}, so two
 * requests at once cannot give one user two codes.
 */
@Entity
@Table(name = "referral_codes")
public class ReferralCode {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "code", nullable = false, updatable = false, unique = true, length = 8)
    private String code;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    protected ReferralCode() { }

    public UUID getUserId() { return userId; }
    public String getCode() { return code; }
    public Instant getCreatedAt() { return createdAt; }
}
