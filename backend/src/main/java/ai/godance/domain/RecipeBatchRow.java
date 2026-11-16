package ai.godance.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
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
import jakarta.persistence.Table;

/**
 * One row of a {@link RecipeBatch} (V36): the value each variable has, the
 * input named for each slot, and what became of it.
 *
 * <p>What the row was given is written once. Its state is changed only by
 * the conditional updates in {@code RecipeBatchRowRepository}: a row is
 * claimed, given its job, and ended, each by an update that matches the row
 * only in the state it must be in. Nothing here writes those columns back.
 */
@Entity
@Table(name = "recipe_batch_rows")
public class RecipeBatchRow {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "batch_id", nullable = false, updatable = false)
    private UUID batchId;

    /** 0, 1, 2… in the order the request gave the rows. */
    @Column(name = "row_index", nullable = false, updatable = false)
    private int rowIndex;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, updatable = false)
    private BatchRowStatus status = BatchRowStatus.PENDING;

    /** The value every variable has in this row; null once the row's job is purged. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "variables", columnDefinition = "jsonb", updatable = false)
    private Map<String, String> variables;

    /** The input named for each slot, as the request wrote it: {@code {"product photo": {"file_id": "…"}}}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "inputs", columnDefinition = "jsonb", nullable = false, updatable = false)
    private Map<String, Object> inputs = new LinkedHashMap<>();

    /** The row's job, written in the transaction that creates the job. */
    @Column(name = "job_id", insertable = false, updatable = false)
    private UUID jobId;

    @Column(name = "cost_cents", insertable = false, updatable = false)
    private Long costCents;

    @Column(name = "error", columnDefinition = "text", insertable = false, updatable = false)
    private String error;

    @Column(name = "started_at", insertable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "completed_at", insertable = false, updatable = false)
    private Instant completedAt;

    /** The model of the row's job when a fallback replaced the batch's (V41); null otherwise. */
    @Column(name = "model", insertable = false, updatable = false)
    private String model;

    /** The failed job a fallback replaced (V41); null when the row was not retried. */
    @Column(name = "retried_job_id", insertable = false, updatable = false)
    private UUID retriedJobId;

    protected RecipeBatchRow() { }

    public RecipeBatchRow(UUID batchId, int rowIndex, Map<String, String> variables, Map<String, Object> inputs) {
        this.batchId = batchId;
        this.rowIndex = rowIndex;
        this.variables = variables;
        this.inputs = inputs;
    }

    public UUID getId() { return id; }

    public UUID getBatchId() { return batchId; }

    public int getRowIndex() { return rowIndex; }

    public BatchRowStatus getStatus() { return status; }

    public Map<String, String> getVariables() { return variables; }

    public Map<String, Object> getInputs() { return inputs == null ? Map.of() : inputs; }

    public UUID getJobId() { return jobId; }

    public Long getCostCents() { return costCents; }

    public String getError() { return error; }

    public Instant getStartedAt() { return startedAt; }

    public Instant getCompletedAt() { return completedAt; }

    public String getModel() { return model; }

    public UUID getRetriedJobId() { return retriedJobId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RecipeBatchRow other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
