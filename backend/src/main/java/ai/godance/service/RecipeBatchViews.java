package ai.godance.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Component;

import ai.godance.domain.BatchRowStatus;
import ai.godance.domain.GenerationJobOutput;
import ai.godance.domain.Recipe;
import ai.godance.domain.RecipeBatch;
import ai.godance.domain.RecipeBatchRow;
import ai.godance.repository.GenerationJobOutputRepository;
import ai.godance.repository.RecipeBatchRowRepository;
import ai.godance.repository.RecipeBatchRowRepository.StatusCount;
import ai.godance.repository.RecipeRepository;
import ai.godance.storage.ObjectStorageClient;
import ai.godance.web.dto.BatchDtos.BatchRowOutput;
import ai.godance.web.dto.BatchDtos.BatchRowView;
import ai.godance.web.dto.BatchDtos.BatchView;

/**
 * A batch and its rows as the API returns them. The counts and
 * {@code spent_cents} are read off the rows every time, never kept on the
 * batch, so they cannot drift from what the rows say: {@code spent_cents}
 * is the sum of what the DONE rows' jobs were charged, and nothing else.
 */
@Component
public class RecipeBatchViews {

    private final RecipeBatchRowRepository rows;
    private final RecipeRepository recipes;
    private final GenerationJobOutputRepository outputs;
    private final ObjectStorageClient storage;

    public RecipeBatchViews(RecipeBatchRowRepository rows, RecipeRepository recipes,
                            GenerationJobOutputRepository outputs, ObjectStorageClient storage) {
        this.rows = rows;
        this.recipes = recipes;
        this.outputs = outputs;
        this.storage = storage;
    }

    public BatchView view(RecipeBatch batch) {
        return views(List.of(batch)).get(0);
    }

    /** The batches in the order given, with their counts in one read and their recipes' titles in another. */
    public List<BatchView> views(List<RecipeBatch> batches) {
        if (batches.isEmpty()) return List.of();
        List<UUID> ids = batches.stream().map(RecipeBatch::getId).toList();
        Map<UUID, Map<String, StatusCount>> counts = new HashMap<>();
        for (StatusCount count : rows.countByStatus(ids)) {
            counts.computeIfAbsent(count.getBatchId(), k -> new HashMap<>()).put(count.getStatus(), count);
        }
        Map<UUID, String> titles = new HashMap<>();
        List<UUID> recipeIds = batches.stream().map(RecipeBatch::getRecipeId).filter(Objects::nonNull).distinct().toList();
        if (!recipeIds.isEmpty()) {
            for (Recipe recipe : recipes.findAllById(recipeIds)) titles.put(recipe.getId(), recipe.getTitle());
        }
        List<BatchView> views = new ArrayList<>(batches.size());
        for (RecipeBatch b : batches) {
            Map<String, StatusCount> of = counts.getOrDefault(b.getId(), Map.of());
            StatusCount done = of.get(BatchRowStatus.DONE.name());
            views.add(new BatchView(b.getId(), b.getRecipeId(),
                    b.getRecipeId() == null ? null : titles.get(b.getRecipeId()),
                    b.getRecipeVersionId(), b.getModality().name(), b.getModel(), b.getParams(),
                    b.getStatus().name(), b.getPauseReason(), b.getPauseDetail(), b.getMaxConcurrency(),
                    b.getRowsTotal(), rowsIn(of, BatchRowStatus.DONE), rowsIn(of, BatchRowStatus.FAILED),
                    rowsIn(of, BatchRowStatus.RUNNING), rowsIn(of, BatchRowStatus.SKIPPED),
                    b.getEstimatedTotalCents(), done == null ? 0 : done.getCostCents(),
                    b.getCreatedAt(), b.getStartedAt(), b.getCompletedAt()));
        }
        return views;
    }

    private static long rowsIn(Map<String, StatusCount> counts, BatchRowStatus status) {
        StatusCount count = counts.get(status.name());
        return count == null ? 0 : count.getRowCount();
    }

    /** Rows as the API returns them, each DONE row with a freshly signed link to every output its job still has. */
    public List<BatchRowView> rowViews(List<RecipeBatchRow> page) {
        Map<UUID, List<GenerationJobOutput>> byJob = outputsOf(page);
        List<BatchRowView> views = new ArrayList<>(page.size());
        for (RecipeBatchRow row : page) {
            List<BatchRowOutput> shown = byJob.getOrDefault(row.getJobId(), List.of()).stream()
                    .map(o -> new BatchRowOutput(o.getOutputIndex(), storage.presignedUrl(o.getStorageKey()),
                            o.getContentType()))
                    .toList();
            views.add(new BatchRowView(row.getRowIndex(), row.getStatus().name(), row.getVariables(),
                    row.getJobId(), row.getCostCents(), row.getError(), shown));
        }
        return views;
    }

    /**
     * The stored outputs of the DONE rows of a page, by job, in output
     * order. A purged job has no output rows, so its row has none here.
     */
    public Map<UUID, List<GenerationJobOutput>> outputsOf(List<RecipeBatchRow> page) {
        List<UUID> jobIds = page.stream()
                .filter(row -> row.getStatus() == BatchRowStatus.DONE && row.getJobId() != null)
                .map(RecipeBatchRow::getJobId)
                .toList();
        Map<UUID, List<GenerationJobOutput>> byJob = new HashMap<>();
        if (jobIds.isEmpty()) return byJob;
        for (GenerationJobOutput output : outputs.findByJobIdInOrderByJobIdAscOutputIndexAsc(jobIds)) {
            byJob.computeIfAbsent(output.getJobId(), k -> new ArrayList<>()).add(output);
        }
        return byJob;
    }
}
