package ai.godance.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * A recipe its author published (V39, round 14 brief 01). The title and the
 * chosen version are copied here when it is published, so what a moderator
 * approved is what the public routes show, whatever the author does to the
 * recipe later. {@code publicId} names it everywhere outside the server.
 */
@Entity
@Table(name = "published_recipes")
public class PublishedRecipe {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "public_id", nullable = false, updatable = false)
    private String publicId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "recipe_id")
    private UUID recipeId;

    @Column(name = "version_id")
    private UUID versionId;

    @Column(name = "version_number", nullable = false, updatable = false)
    private int versionNumber;

    @Column(name = "title", nullable = false, updatable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "modality", nullable = false, updatable = false)
    private Modality modality;

    @Column(name = "template", columnDefinition = "text", nullable = false, updatable = false)
    private String template;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "variables", columnDefinition = "jsonb", nullable = false, updatable = false)
    private List<Map<String, String>> variables = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "inputs", columnDefinition = "jsonb", nullable = false, updatable = false)
    private List<Map<String, String>> inputs = new ArrayList<>();

    @Column(name = "model", nullable = false, updatable = false)
    private String model;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "params", columnDefinition = "jsonb", nullable = false, updatable = false)
    private Map<String, Object> params = new LinkedHashMap<>();

    @Column(name = "licence", nullable = false, updatable = false)
    private String licence;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tags", columnDefinition = "jsonb", nullable = false, updatable = false)
    private List<String> tags = new ArrayList<>();

    @Column(name = "made_from_gallery", updatable = false)
    private String madeFromGallery;

    @Column(name = "made_from_published_id")
    private UUID madeFromPublishedId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PublishedStatus status;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "reason", columnDefinition = "text")
    private String reason;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "removed_at")
    private Instant removedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (submittedAt == null) submittedAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }

    public String getPublicId() { return publicId; }
    public void setPublicId(String publicId) { this.publicId = publicId; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public UUID getRecipeId() { return recipeId; }
    public void setRecipeId(UUID recipeId) { this.recipeId = recipeId; }

    public UUID getVersionId() { return versionId; }
    public void setVersionId(UUID versionId) { this.versionId = versionId; }

    public int getVersionNumber() { return versionNumber; }
    public void setVersionNumber(int versionNumber) { this.versionNumber = versionNumber; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public Modality getModality() { return modality; }
    public void setModality(Modality modality) { this.modality = modality; }

    public String getTemplate() { return template; }
    public void setTemplate(String template) { this.template = template; }

    public List<Map<String, String>> getVariables() { return variables == null ? List.of() : variables; }
    public void setVariables(List<Map<String, String>> variables) { this.variables = variables; }

    public List<Map<String, String>> getInputs() { return inputs == null ? List.of() : inputs; }
    public void setInputs(List<Map<String, String>> inputs) { this.inputs = inputs; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public Map<String, Object> getParams() { return params == null ? Map.of() : params; }
    public void setParams(Map<String, Object> params) { this.params = params; }

    public String getLicence() { return licence; }
    public void setLicence(String licence) { this.licence = licence; }

    public List<String> getTags() { return tags == null ? List.of() : tags; }
    public void setTags(List<String> tags) { this.tags = tags; }

    public String getMadeFromGallery() { return madeFromGallery; }
    public void setMadeFromGallery(String madeFromGallery) { this.madeFromGallery = madeFromGallery; }

    public UUID getMadeFromPublishedId() { return madeFromPublishedId; }
    public void setMadeFromPublishedId(UUID madeFromPublishedId) { this.madeFromPublishedId = madeFromPublishedId; }

    public PublishedStatus getStatus() { return status; }
    public void setStatus(PublishedStatus status) { this.status = status; }

    public UUID getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(UUID reviewedBy) { this.reviewedBy = reviewedBy; }

    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public Instant getSubmittedAt() { return submittedAt; }

    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }

    public Instant getRemovedAt() { return removedAt; }
    public void setRemovedAt(Instant removedAt) { this.removedAt = removedAt; }

    public Instant getUpdatedAt() { return updatedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PublishedRecipe other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
