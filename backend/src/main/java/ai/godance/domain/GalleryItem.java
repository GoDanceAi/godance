package ai.godance.domain;

import java.time.Instant;
import java.util.List;
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
import jakarta.persistence.Table;

@Entity
@Table(name = "gallery_items")
public class GalleryItem {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "job_id")
    private UUID jobId;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "prompt", nullable = false, columnDefinition = "text")
    private String prompt;

    @Column(name = "negative_prompt", columnDefinition = "text")
    private String negativePrompt;

    @Column(name = "author_name", nullable = false)
    private String authorName;

    @Enumerated(EnumType.STRING)
    @Column(name = "modality", nullable = false)
    private Modality modality;

    @Column(name = "model", nullable = false)
    private String model;

    @Column(name = "provider", nullable = false)
    private String provider;

    @Column(name = "aspect_ratio", nullable = false)
    private String aspectRatio = "1:1";

    @Column(name = "duration")
    private String duration;

    @Column(name = "seed")
    private Long seed;

    @Column(name = "output_url", nullable = false)
    private String outputUrl;

    @Column(name = "thumbnail_url", nullable = false)
    private String thumbnailUrl;

    @Column(name = "likes_count", nullable = false)
    private int likesCount = 0;

    @Column(name = "remix_count", nullable = false)
    private int remixCount = 0;

    @Column(name = "is_official", nullable = false)
    private boolean official = false;

    @Column(name = "category", nullable = false)
    private String category = "ART";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tags", columnDefinition = "jsonb")
    private List<String> tags;

    @Column(name = "forked_from_title")
    private String forkedFromTitle;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public GalleryItem() {}

    @PrePersist
    void onPrePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (aspectRatio == null) {
            aspectRatio = "1:1";
        }
        if (category == null) {
            category = "ART";
        }
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public UUID getJobId() { return jobId; }
    public void setJobId(UUID jobId) { this.jobId = jobId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }

    public String getNegativePrompt() { return negativePrompt; }
    public void setNegativePrompt(String negativePrompt) { this.negativePrompt = negativePrompt; }

    public String getAuthorName() { return authorName; }
    public void setAuthorName(String authorName) { this.authorName = authorName; }

    public Modality getModality() { return modality; }
    public void setModality(Modality modality) { this.modality = modality; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getAspectRatio() { return aspectRatio; }
    public void setAspectRatio(String aspectRatio) { this.aspectRatio = aspectRatio; }

    public String getDuration() { return duration; }
    public void setDuration(String duration) { this.duration = duration; }

    public Long getSeed() { return seed; }
    public void setSeed(Long seed) { this.seed = seed; }

    public String getOutputUrl() { return outputUrl; }
    public void setOutputUrl(String outputUrl) { this.outputUrl = outputUrl; }

    public String getThumbnailUrl() { return thumbnailUrl; }
    public void setThumbnailUrl(String thumbnailUrl) { this.thumbnailUrl = thumbnailUrl; }

    public int getLikesCount() { return likesCount; }
    public void setLikesCount(int likesCount) { this.likesCount = likesCount; }

    public int getRemixCount() { return remixCount; }
    public void setRemixCount(int remixCount) { this.remixCount = remixCount; }

    public boolean isOfficial() { return official; }
    public void setOfficial(boolean official) { this.official = official; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }

    public String getForkedFromTitle() { return forkedFromTitle; }
    public void setForkedFromTitle(String forkedFromTitle) { this.forkedFromTitle = forkedFromTitle; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GalleryItem item)) return false;
        return Objects.equals(id, item.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
