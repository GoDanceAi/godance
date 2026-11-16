package ai.godance.mcp;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import io.modelcontextprotocol.server.McpStatelessServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import ai.godance.service.BatchRefusedException;
import ai.godance.service.JobReadService;
import ai.godance.service.RecipeBatchService;
import ai.godance.web.dto.BatchDtos.BatchView;
import ai.godance.web.dto.ImageDtos.JobView;
import reactor.core.publisher.Mono;

/**
 * The tools that follow what the spending tools started: {@code get_job} and
 * {@code get_batch}. Each answers exactly what {@code GET /v1/jobs/{id}} and
 * {@code GET /v1/recipe-batches/{id}} answer, for the key's own jobs and
 * batches only: another user's answers 404, as one that does not exist.
 */
@Component
class FollowTools implements McpToolProvider {

    private final ToolSupport support;
    private final JobReadService jobs;
    private final RecipeBatchService batches;

    FollowTools(ToolSupport support, JobReadService jobs, RecipeBatchService batches) {
        this.support = support;
        this.jobs = jobs;
        this.batches = batches;
    }

    @Override
    public List<AsyncToolSpecification> tools() {
        return List.of(
                support.spec(support.readOnly("get_job", "Get a job", JOB_DESCRIPTION, JOB_INPUT, JOB_OUTPUT),
                        this::getJob),
                support.spec(support.readOnly("get_batch", "Get a batch", BATCH_DESCRIPTION, BATCH_INPUT,
                        BATCH_OUTPUT), this::getBatch));
    }

    private Mono<CallToolResult> getJob(McpCaller caller, ToolArgs args) {
        UUID jobId = args.id("job_id", () -> ToolArgs.badRequest("job_id must be a job id"));
        if (jobId == null) throw ToolArgs.badRequest("job_id is required");
        return ToolSupport.blocking(() -> {
            JobView job = jobs.get(caller.userId(), jobId);
            return support.results().success(jobSummary(job), job);
        });
    }

    private Mono<CallToolResult> getBatch(McpCaller caller, ToolArgs args) {
        UUID batchId = args.id("batch_id", BatchRefusedException::notFound);
        if (batchId == null) throw ToolArgs.badRequest("batch_id is required");
        Integer rowsLimit = args.integer("rows_limit");
        Integer rowsAfter = args.integer("rows_after");
        boolean withRows = rowsLimit != null || rowsAfter != null;
        return ToolSupport.blocking(() -> {
            BatchView batch = batches.get(caller.userId(), batchId);
            Map<String, Object> out = support.results().toMap(batch);
            if (withRows) out.put("rows", batches.rows(caller.userId(), batchId, rowsLimit, rowsAfter));
            String summary = "Batch " + batch.id() + " is " + batch.status() + ": " + batch.rowsDone() + " of "
                    + batch.rowsTotal() + " rows done, " + batch.rowsFailed() + " failed, " + batch.rowsRunning()
                    + " running; " + batch.spentCents() + " of an estimated " + batch.estimatedTotalCents()
                    + " cents spent."
                    + (batch.pauseReason() == null ? "" : " Paused: " + batch.pauseReason()
                            + (batch.pauseDetail() == null ? "" : " (" + batch.pauseDetail() + ")")
                            + "; the user can resume it in the app or with POST /v1/recipe-batches/{id}/resume.");
            return support.results().success(summary, out);
        });
    }

    private static String jobSummary(JobView job) {
        StringBuilder line = new StringBuilder("Job ").append(job.id()).append(" is ").append(job.status());
        switch (job.status()) {
            case DONE -> line.append(": ").append(job.outputs().size()).append(" output(s) as signed links that "
                    + "expire (call get_job again for fresh ones); charged ").append(job.actualCostCents())
                    .append(" cents.");
            case FAILED, CANCELLED -> line.append(job.errorMessage() == null ? "" : ": " + job.errorMessage())
                    .append(". Its hold was refunded.");
            default -> line.append("; call get_job again in a few seconds (10 to 30 for video).");
        }
        return line.toString();
    }

    static final String JOB_DESCRIPTION = """
            Status, outputs and cost of one of the caller's jobs (from generate_image, generate_video, \
            run_recipe or a batch row). status is QUEUED, RUNNING, DONE, FAILED or CANCELLED. A DONE job's \
            outputs are signed links that expire: call get_job again for fresh ones. A FAILED job was \
            refunded. Free.""";

    static final String JOB_INPUT = """
            {"type": "object",
             "properties": {"job_id": {"type": "string", "description": "From a spending tool's answer."}},
             "required": ["job_id"],
             "additionalProperties": false}""";

    static final String JOB_OUTPUT = """
            {"type": "object",
             "properties": {
               "id": {"type": "string"},
               "status": {"type": "string", "enum": ["QUEUED", "RUNNING", "DONE", "FAILED", "CANCELLED"]},
               "outputs": {"type": "array", "items": {"type": "object"}}
             },
             "required": ["id", "status"]}""";

    static final String BATCH_DESCRIPTION = """
            Status and row counts of one of the caller's recipe batches, with what it was estimated to cost \
            and what it has spent; a paused batch says why (insufficient_credit or spending_cap). With \
            rows_limit or rows_after, also a page of its rows in order, each with its job, cost, error and \
            outputs as signed links. Free.""";

    static final String BATCH_INPUT = """
            {"type": "object",
             "properties": {
               "batch_id": {"type": "string", "description": "From start_batch."},
               "rows_limit": {"type": "integer", "minimum": 1, "maximum": 100, "description": "How many rows to include; 50 when only rows_after is given."},
               "rows_after": {"type": "integer", "minimum": -1, "description": "The index of the last row already seen; rows after it are returned."}
             },
             "required": ["batch_id"],
             "additionalProperties": false}""";

    static final String BATCH_OUTPUT = """
            {"type": "object",
             "properties": {
               "id": {"type": "string"},
               "status": {"type": "string"},
               "rows_total": {"type": "integer"},
               "rows_done": {"type": "integer"},
               "rows_failed": {"type": "integer"},
               "spent_cents": {"type": "integer"},
               "estimated_total_cents": {"type": "integer"},
               "rows": {"type": "array", "items": {"type": "object"}}
             },
             "required": ["id", "status"]}""";
}
