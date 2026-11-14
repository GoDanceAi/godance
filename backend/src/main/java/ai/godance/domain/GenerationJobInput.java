package ai.godance.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * One input of a generation job (V31): an uploaded file ({@code fileId}), or
 * an output of one of the same user's jobs ({@code sourceJobId},
 * {@code sourceIndex}). Exactly one of the two. {@code position} is the
 * order the request gave them in.
 *
 * <p>Written with the job, deleted when the job is purged; never changed.
 * Read-only as an entity: {@code GenerationJobInputRepository} inserts and
 * deletes with its own statements.
 */
@Entity
@Table(name = "generation_job_inputs")
@IdClass(GenerationJobInput.Key.class)
public class GenerationJobInput {

    /** The row's key: a job and a position within it. */
    public static class Key implements Serializable {
        private UUID jobId;
        private int position;

        public Key() { }

        public Key(UUID jobId, int position) {
            this.jobId = jobId;
            this.position = position;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key other && position == other.position && Objects.equals(jobId, other.jobId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(jobId, position);
        }
    }

    @Id
    @Column(name = "job_id", nullable = false, updatable = false)
    private UUID jobId;

    @Id
    @Column(name = "position", nullable = false, updatable = false)
    private int position;

    /** {@link InputRole#wire()}. */
    @Column(name = "role", nullable = false, updatable = false)
    private String role;

    @Column(name = "file_id", updatable = false)
    private UUID fileId;

    @Column(name = "source_job_id", updatable = false)
    private UUID sourceJobId;

    @Column(name = "source_index", updatable = false)
    private Integer sourceIndex;

    protected GenerationJobInput() { }

    public UUID getJobId() { return jobId; }
    public int getPosition() { return position; }
    public InputRole getRole() { return InputRole.fromWire(role); }
    public UUID getFileId() { return fileId; }
    public UUID getSourceJobId() { return sourceJobId; }
    public Integer getSourceIndex() { return sourceIndex; }
}
