package ai.godance.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * A report on a published recipe or a profile (V39). An open report on a
 * public recipe puts it back in front of a moderator; the recipe stays
 * public unless the moderator removes it.
 */
@Entity
@Table(name = "reports")
public class Report {

    /** What a report is about, as the API names it. */
    public static final String KIND_PUBLISHED_RECIPE = "published_recipe";
    public static final String KIND_PROFILE = "profile";

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_RESOLVED = "RESOLVED";

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "reporter_id", updatable = false)
    private UUID reporterId;

    @Column(name = "kind", nullable = false, updatable = false)
    private String kind;

    @Column(name = "published_recipe_id", updatable = false)
    private UUID publishedRecipeId;

    @Column(name = "profile_user_id", updatable = false)
    private UUID profileUserId;

    @Column(name = "reason", columnDefinition = "text", nullable = false, updatable = false)
    private String reason;

    @Column(name = "status", nullable = false)
    private String status = STATUS_OPEN;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }

    public UUID getReporterId() { return reporterId; }
    public void setReporterId(UUID reporterId) { this.reporterId = reporterId; }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public UUID getPublishedRecipeId() { return publishedRecipeId; }
    public void setPublishedRecipeId(UUID publishedRecipeId) { this.publishedRecipeId = publishedRecipeId; }

    public UUID getProfileUserId() { return profileUserId; }
    public void setProfileUserId(UUID profileUserId) { this.profileUserId = profileUserId; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public UUID getResolvedBy() { return resolvedBy; }
    public void setResolvedBy(UUID resolvedBy) { this.resolvedBy = resolvedBy; }

    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }

    public Instant getCreatedAt() { return createdAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Report other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
