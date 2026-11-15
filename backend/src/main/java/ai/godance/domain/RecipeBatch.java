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
import jakarta.persistence.Table;

/**
 * One version of a recipe run over many rows (V36). The batch is the plan
 * and its state; each row ({@link RecipeBatchRow}) becomes one ordinary
 * {@link GenerationJob}. A batch holds no money: every charge is a job's.
 *
 * <p>What the batch was asked for never changes once it is written. Its
 * state ({@code status}, the pause, the times) is changed only by the
 * conditional updates in {@code RecipeBatchRepository}, so two writers can
 * race and only one of them wins; nothing here writes those columns back.
 */
@Entity
@Table(name = "recipe_batches")
public class RecipeBatch {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** How the batch was made: {@link JobChannel#STUDIO} or {@link JobChannel#API}. Never BATCH. */
    @Enumerated(EnumType.STRING)
    @Column(name = "created_by_channel", nullable = false, updatable = false)
    private JobChannel createdByChannel;

    /** The API key that made the batch; the database clears it when the key is deleted. */
    @Column(name = "api_key_id", updatable = false)
    private UUID apiKeyId;

    /** The recipe; the database clears it when the recipe is deleted. */
    @Column(name = "recipe_id", updatable = false)
    private UUID recipeId;

    /** The version every row runs; the database clears it when the recipe is deleted. */
    @Column(name = "recipe_version_id", updatable = false)
    private UUID recipeVersionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "modality", nullable = false, updatable = false)
    private Modality modality;

    @Column(name = "model", nullable = false, updatable = false)
    private String model;

    /** Request fields as the API names them: size, n, seconds, … */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "params", columnDefinition = "jsonb", nullable = false, updatable = false)
    private Map<String, Object> params = new LinkedHashMap<>();

    /** The version's variable names, in order: the columns of the results file. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "variable_names", columnDefinition = "jsonb", nullable = false, updatable = false)
    private List<String> variableNames = new ArrayList<>();

    @Column(name = "max_concurrency", nullable = false, updatable = false)
    private int maxConcurrency;

    @Column(name = "webhook_url", updatable = false)
    private String webhookUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, updatable = false)
    private BatchStatus status;

    /** {@code insufficient_credit} or {@code spending_cap} while PAUSED; null otherwise. */
    @Column(name = "pause_reason", insertable = false, updatable = false)
    private String pauseReason;

    /** Which cap, in words, for a {@code spending_cap} pause. */
    @Column(name = "pause_detail", insertable = false, updatable = false)
    private String pauseDetail;

    @Column(name = "rows_total", nullable = false, updatable = false)
    private int rowsTotal;

    @Column(name = "estimated_total_cents", nullable = false, updatable = false)
    private long estimatedTotalCents;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at", insertable = false, updatable = false)
    private Instant startedAt;

    /** When the last row ended; null while any row is pending or running. */
    @Column(name = "completed_at", insertable = false, updatable = false)
    private Instant completedAt;

    @Column(name = "webhook_sent_at", insertable = false, updatable = false)
    private Instant webhookSentAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (status == null) status = BatchStatus.QUEUED;
    }

    public UUID getId() { return id; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public JobChannel getCreatedByChannel() { return createdByChannel; }
    public void setCreatedByChannel(JobChannel createdByChannel) { this.createdByChannel = createdByChannel; }

    public UUID getApiKeyId() { return apiKeyId; }
    public void setApiKeyId(UUID apiKeyId) { this.apiKeyId = apiKeyId; }

    public UUID getRecipeId() { return recipeId; }
    public void setRecipeId(UUID recipeId) { this.recipeId = recipeId; }

    public UUID getRecipeVersionId() { return recipeVersionId; }
    public void setRecipeVersionId(UUID recipeVersionId) { this.recipeVersionId = recipeVersionId; }

    public Modality getModality() { return modality; }
    public void setModality(Modality modality) { this.modality = modality; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public Map<String, Object> getParams() { return params == null ? Map.of() : params; }
    public void setParams(Map<String, Object> params) { this.params = params; }

    public List<String> getVariableNames() { return variableNames == null ? List.of() : variableNames; }
    public void setVariableNames(List<String> variableNames) { this.variableNames = variableNames; }

    public int getMaxConcurrency() { return maxConcurrency; }
    public void setMaxConcurrency(int maxConcurrency) { this.maxConcurrency = maxConcurrency; }

    public String getWebhookUrl() { return webhookUrl; }
    public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }

    public BatchStatus getStatus() { return status; }

    public String getPauseReason() { return pauseReason; }

    public String getPauseDetail() { return pauseDetail; }

    public int getRowsTotal() { return rowsTotal; }
    public void setRowsTotal(int rowsTotal) { this.rowsTotal = rowsTotal; }

    public long getEstimatedTotalCents() { return estimatedTotalCents; }
    public void setEstimatedTotalCents(long estimatedTotalCents) { this.estimatedTotalCents = estimatedTotalCents; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getStartedAt() { return startedAt; }

    public Instant getCompletedAt() { return completedAt; }

    public Instant getWebhookSentAt() { return webhookSentAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RecipeBatch other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
