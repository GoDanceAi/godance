package ai.godance.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import ai.godance.web.dto.InputDtos.InputRef;

/** {@code /v1/recipe-batches/**}, as the round-12 contract has them. Field names are snake_case. */
public final class BatchDtos {

    private BatchDtos() { }

    /**
     * One row of a batch request: a value for any of the recipe's variables
     * (the others keep their defaults), and an input for each of its slots,
     * by slot name, as {@code recipe.inputs} has them on a single run.
     */
    public record BatchRowRequest(
            Map<String, String> variables,
            Map<String, InputRef> inputs
    ) { }

    /**
     * {@code POST /v1/recipe-batches} and {@code …/estimate}. {@code model}
     * and {@code params} default to the version's; a {@code params} that is
     * sent replaces the version's as a whole.
     */
    public record BatchRequest(
            @JsonProperty("version_id") UUID versionId,
            String model,
            Map<String, Object> params,
            List<BatchRowRequest> rows,
            @JsonProperty("max_concurrency") Integer maxConcurrency,
            @JsonProperty("webhook_url") String webhookUrl,
            // Round 14: retry a row once on another model, as the caller's routing policies' fallbacks say.
            Boolean fallbacks
    ) { }

    /** What the server allows a batch: for a client that offers the choice. */
    public record BatchLimits(
            @JsonProperty("max_rows") int maxRows,
            @JsonProperty("max_concurrency") int maxConcurrency
    ) { }

    /**
     * What a batch would cost: the sum of what each row's job would
     * pre-debit. {@code enough} compares it with the balance now; a batch
     * can be created either way, and pauses when credit runs out.
     */
    public record BatchEstimate(
            int rows,
            @JsonProperty("total_cents") long totalCents,
            @JsonProperty("balance_cents") long balanceCents,
            boolean enough,
            BatchLimits limits
    ) { }

    /**
     * A batch. {@code spent_cents} is what its finished rows' jobs were
     * charged, and nothing else: a batch charges nothing itself.
     * {@code recipe_id}, {@code recipe_title} and {@code version_id} are
     * null once the recipe has been deleted. {@code completed_at} is when
     * the last row ended; a cancelled batch has none until the rows that
     * were running have finished.
     */
    public record BatchView(
            UUID id,
            @JsonInclude(JsonInclude.Include.ALWAYS) @JsonProperty("recipe_id") UUID recipeId,
            @JsonInclude(JsonInclude.Include.ALWAYS) @JsonProperty("recipe_title") String recipeTitle,
            @JsonInclude(JsonInclude.Include.ALWAYS) @JsonProperty("version_id") UUID versionId,
            String modality,
            String model,
            Map<String, Object> params,
            String status,
            @JsonInclude(JsonInclude.Include.ALWAYS) @JsonProperty("pause_reason") String pauseReason,
            @JsonInclude(JsonInclude.Include.ALWAYS) @JsonProperty("pause_detail") String pauseDetail,
            @JsonProperty("max_concurrency") int maxConcurrency,
            @JsonProperty("rows_total") int rowsTotal,
            @JsonProperty("rows_done") long rowsDone,
            @JsonProperty("rows_failed") long rowsFailed,
            @JsonProperty("rows_running") long rowsRunning,
            @JsonProperty("rows_skipped") long rowsSkipped,
            @JsonProperty("estimated_total_cents") long estimatedTotalCents,
            @JsonProperty("spent_cents") long spentCents,
            @JsonProperty("created_at") Instant createdAt,
            @JsonInclude(JsonInclude.Include.ALWAYS) @JsonProperty("started_at") Instant startedAt,
            @JsonInclude(JsonInclude.Include.ALWAYS) @JsonProperty("completed_at") Instant completedAt,
            // True for a batch created with "fallbacks": true; left out otherwise, as it always was.
            @JsonInclude(JsonInclude.Include.NON_NULL) Boolean fallbacks
    ) { }

    /** One stored output of a row's job, with a link signed when the row is read. */
    public record BatchRowOutput(
            int index,
            String url,
            @JsonProperty("content_type") String contentType
    ) { }

    /**
     * One row. {@code variables} is null once the row's job has been purged.
     * {@code cost_cents} is what the row's job was charged: null until the
     * row has ended, 0 for a row that failed. {@code outputs} is empty
     * unless the row is DONE and its files are still kept.
     */
    public record BatchRowView(
            int index,
            String status,
            @JsonInclude(JsonInclude.Include.ALWAYS) Map<String, String> variables,
            @JsonInclude(JsonInclude.Include.ALWAYS) @JsonProperty("job_id") UUID jobId,
            @JsonInclude(JsonInclude.Include.ALWAYS) @JsonProperty("cost_cents") Long costCents,
            @JsonInclude(JsonInclude.Include.ALWAYS) String error,
            List<BatchRowOutput> outputs,
            // A row retried on a fallback: the model its job ran on, and the failed job it replaced.
            // Left out for every other row, whose model is the batch's.
            @JsonInclude(JsonInclude.Include.NON_NULL) String model,
            @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("retried_job_id") UUID retriedJobId
    ) { }
}
