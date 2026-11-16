package ai.godance.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * An account's public name (V39, round 14 brief 01). {@code displayName}
 * and {@code link} are what the owner last set; the {@code approved*}
 * fields are what a moderator last approved, and the only values a public
 * route shows. The handle cannot change once the profile is approved.
 */
@Entity
@Table(name = "profiles")
public class Profile {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "handle", nullable = false)
    private String handle;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "link")
    private String link;

    @Column(name = "approved_display_name")
    private String approvedDisplayName;

    @Column(name = "approved_link")
    private String approvedLink;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "review_reason", columnDefinition = "text")
    private String reviewReason;

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

    /** Whether a moderator has approved this profile at least once: its handle is then fixed and it may be public. */
    public boolean isApproved() {
        return approvedAt != null;
    }

    /**
     * Whether the owner changed the display name or link since a moderator
     * last approved them, and no moderator has refused that change.
     */
    public boolean hasChangeAwaitingReview() {
        return isApproved() && reviewReason == null
                && (!Objects.equals(displayName, approvedDisplayName) || !Objects.equals(link, approvedLink));
    }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public String getHandle() { return handle; }
    public void setHandle(String handle) { this.handle = handle; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getLink() { return link; }
    public void setLink(String link) { this.link = link; }

    public String getApprovedDisplayName() { return approvedDisplayName; }
    public void setApprovedDisplayName(String approvedDisplayName) { this.approvedDisplayName = approvedDisplayName; }

    public String getApprovedLink() { return approvedLink; }
    public void setApprovedLink(String approvedLink) { this.approvedLink = approvedLink; }

    public Instant getApprovedAt() { return approvedAt; }
    public void setApprovedAt(Instant approvedAt) { this.approvedAt = approvedAt; }

    public String getReviewReason() { return reviewReason; }
    public void setReviewReason(String reviewReason) { this.reviewReason = reviewReason; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Profile other)) return false;
        return userId != null && userId.equals(other.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(userId);
    }
}
