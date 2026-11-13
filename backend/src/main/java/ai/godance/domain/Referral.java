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
import jakarta.persistence.Table;

/**
 * One invited user and who invited them (V26). Written when the invited
 * user claims a code, and settled once: {@link Status#REWARDED} on their
 * first qualifying top-up, with what each side got, or {@link Status#VOID}
 * when that can no longer happen. The credits themselves are REFERRAL rows
 * in {@code credit_ledger}.
 */
@Entity
@Table(name = "referrals")
public class Referral {

    public enum Status { PENDING, REWARDED, VOID }

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "referrer_id", nullable = false, updatable = false)
    private UUID referrerId;

    @Column(name = "invited_user_id", nullable = false, updatable = false, unique = true)
    private UUID invitedUserId;

    @Column(name = "claimed_at", nullable = false, updatable = false)
    private Instant claimedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status = Status.PENDING;

    @Column(name = "rewarded_at")
    private Instant rewardedAt;

    @Column(name = "rewarded_topup_id")
    private UUID rewardedTopUpId;

    @Column(name = "referrer_reward_cents")
    private Long referrerRewardCents;

    @Column(name = "referee_reward_cents")
    private Long refereeRewardCents;

    protected Referral() { }

    public Referral(UUID referrerId, UUID invitedUserId, Instant claimedAt) {
        this.referrerId = referrerId;
        this.invitedUserId = invitedUserId;
        this.claimedAt = claimedAt;
    }

    /** Settles the referral on {@code topUpId}, recording what each side got. */
    public void markRewarded(Instant at, UUID topUpId, long referrerCents, long refereeCents) {
        this.status = Status.REWARDED;
        this.rewardedAt = at;
        this.rewardedTopUpId = topUpId;
        this.referrerRewardCents = referrerCents;
        this.refereeRewardCents = refereeCents;
    }

    public void markVoid() {
        this.status = Status.VOID;
    }

    public UUID getId() { return id; }
    public UUID getReferrerId() { return referrerId; }
    public UUID getInvitedUserId() { return invitedUserId; }
    public Instant getClaimedAt() { return claimedAt; }
    public Status getStatus() { return status; }
    public Instant getRewardedAt() { return rewardedAt; }
    public UUID getRewardedTopUpId() { return rewardedTopUpId; }
    public Long getReferrerRewardCents() { return referrerRewardCents; }
    public Long getRefereeRewardCents() { return refereeRewardCents; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Referral other)) return false;
        return id != null && Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
