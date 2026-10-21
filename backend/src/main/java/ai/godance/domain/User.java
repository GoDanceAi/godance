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

@Entity
@Table(name = "users")
public class User {

    /**
     * Ends the placeholder address given to an OAuth user whose provider
     * shares no email (X.com). Nothing can be delivered to it.
     */
    public static final String SYNTHETIC_EMAIL_SUFFIX = "@oauth.godance.local";

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private UserRole role;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "credit_balance_cents", nullable = false)
    private long creditBalanceCents = 0L;

    @Column(name = "daily_credit_cap_cents")
    private Long dailyCreditCapCents;

    @Column(name = "monthly_credit_cap_cents")
    private Long monthlyCreditCapCents;

    /** Last time the 80% soft-cap warning fired for the daily window. */
    @Column(name = "daily_cap_warned_at")
    private Instant dailyCapWarnedAt;

    /** Last time the 80% soft-cap warning fired for the monthly window. */
    @Column(name = "monthly_cap_warned_at")
    private Instant monthlyCapWarnedAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified = false;

    @Column(name = "email_verification_token")
    private String emailVerificationToken;

    @Column(name = "email_verification_sent_at")
    private Instant emailVerificationSentAt;

    /** Address being added to a placeholder account; becomes {@code email} once verified. */
    @Column(name = "pending_email")
    private String pendingEmail;

    @Column(name = "stripe_customer_id", unique = true)
    private String stripeCustomerId;

    @Column(name = "webhook_signing_secret")
    private String webhookSigningSecret;

    @Column(name = "webhook_signing_secret_previous")
    private String webhookSigningSecretPrevious;

    @Column(name = "webhook_signing_secret_rotated_at")
    private Instant webhookSigningSecretRotatedAt;

    // ----- Auto-recharge (Session 13) ----------------------------------
    @Column(name = "auto_recharge_enabled", nullable = false)
    private boolean autoRechargeEnabled = false;

    /** When the balance drops below this, fire an off-session top-up. */
    @Column(name = "auto_recharge_threshold_cents")
    private Long autoRechargeThresholdCents;

    /** How much to charge each time auto-recharge fires. */
    @Column(name = "auto_recharge_topup_cents")
    private Long autoRechargeTopupCents;

    /**
     * Stripe payment-method handle (pm_xxx) captured during the user's first
     * top-up with setup_future_usage=off_session. Not a secret — same trust
     * level as {@link #stripeCustomerId}.
     */
    @Column(name = "stripe_payment_method_id")
    private String stripePaymentMethodId;

    @Column(name = "auto_recharge_last_failure_at")
    private Instant autoRechargeLastFailureAt;

    @Column(name = "auto_recharge_last_failure_reason", columnDefinition = "text")
    private String autoRechargeLastFailureReason;

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

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public UserRole getRole() { return role; }
    public void setRole(UserRole role) { this.role = role; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public long getCreditBalanceCents() { return creditBalanceCents; }
    public void setCreditBalanceCents(long creditBalanceCents) { this.creditBalanceCents = creditBalanceCents; }

    public Long getDailyCreditCapCents() { return dailyCreditCapCents; }
    public void setDailyCreditCapCents(Long dailyCreditCapCents) { this.dailyCreditCapCents = dailyCreditCapCents; }

    public Long getMonthlyCreditCapCents() { return monthlyCreditCapCents; }
    public void setMonthlyCreditCapCents(Long monthlyCreditCapCents) { this.monthlyCreditCapCents = monthlyCreditCapCents; }

    public Instant getDailyCapWarnedAt() { return dailyCapWarnedAt; }
    public void setDailyCapWarnedAt(Instant dailyCapWarnedAt) { this.dailyCapWarnedAt = dailyCapWarnedAt; }

    public Instant getMonthlyCapWarnedAt() { return monthlyCapWarnedAt; }
    public void setMonthlyCapWarnedAt(Instant monthlyCapWarnedAt) { this.monthlyCapWarnedAt = monthlyCapWarnedAt; }

    public Instant getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(Instant lastLoginAt) { this.lastLoginAt = lastLoginAt; }

    public boolean isEmailVerified() { return emailVerified; }
    public void setEmailVerified(boolean emailVerified) { this.emailVerified = emailVerified; }

    /** True when {@link #getEmail()} is a placeholder rather than a real inbox. */
    public boolean hasSyntheticEmail() {
        return email != null && email.endsWith(SYNTHETIC_EMAIL_SUFFIX);
    }

    public String getEmailVerificationToken() { return emailVerificationToken; }
    public void setEmailVerificationToken(String emailVerificationToken) { this.emailVerificationToken = emailVerificationToken; }

    public Instant getEmailVerificationSentAt() { return emailVerificationSentAt; }
    public void setEmailVerificationSentAt(Instant emailVerificationSentAt) { this.emailVerificationSentAt = emailVerificationSentAt; }

    public String getPendingEmail() { return pendingEmail; }
    public void setPendingEmail(String pendingEmail) { this.pendingEmail = pendingEmail; }

    public String getStripeCustomerId() { return stripeCustomerId; }
    public void setStripeCustomerId(String stripeCustomerId) { this.stripeCustomerId = stripeCustomerId; }

    public String getWebhookSigningSecret() { return webhookSigningSecret; }
    public void setWebhookSigningSecret(String webhookSigningSecret) { this.webhookSigningSecret = webhookSigningSecret; }

    public String getWebhookSigningSecretPrevious() { return webhookSigningSecretPrevious; }
    public void setWebhookSigningSecretPrevious(String s) { this.webhookSigningSecretPrevious = s; }

    public Instant getWebhookSigningSecretRotatedAt() { return webhookSigningSecretRotatedAt; }
    public void setWebhookSigningSecretRotatedAt(Instant t) { this.webhookSigningSecretRotatedAt = t; }

    public boolean isAutoRechargeEnabled() { return autoRechargeEnabled; }
    public void setAutoRechargeEnabled(boolean autoRechargeEnabled) { this.autoRechargeEnabled = autoRechargeEnabled; }

    public Long getAutoRechargeThresholdCents() { return autoRechargeThresholdCents; }
    public void setAutoRechargeThresholdCents(Long v) { this.autoRechargeThresholdCents = v; }

    public Long getAutoRechargeTopupCents() { return autoRechargeTopupCents; }
    public void setAutoRechargeTopupCents(Long v) { this.autoRechargeTopupCents = v; }

    public String getStripePaymentMethodId() { return stripePaymentMethodId; }
    public void setStripePaymentMethodId(String stripePaymentMethodId) { this.stripePaymentMethodId = stripePaymentMethodId; }

    public Instant getAutoRechargeLastFailureAt() { return autoRechargeLastFailureAt; }
    public void setAutoRechargeLastFailureAt(Instant t) { this.autoRechargeLastFailureAt = t; }

    public String getAutoRechargeLastFailureReason() { return autoRechargeLastFailureReason; }
    public void setAutoRechargeLastFailureReason(String r) { this.autoRechargeLastFailureReason = r; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof User other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
