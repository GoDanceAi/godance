package ai.godance.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.config.BatchesProperties;
import ai.godance.domain.AuditOutcome;
import ai.godance.domain.JobChannel;
import ai.godance.domain.Modality;
import ai.godance.domain.Recipe;
import ai.godance.domain.RecipeBatch;
import ai.godance.domain.RecipeBatchRow;
import ai.godance.domain.RecipeVersion;
import ai.godance.domain.User;
import ai.godance.repository.RecipeBatchRepository;
import ai.godance.repository.RecipeBatchRowRepository;
import ai.godance.repository.RecipeRepository;
import ai.godance.repository.RecipeVersionRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.GenerationQuotes.Quote;
import ai.godance.service.RecipeRunService.RunPrompt;
import ai.godance.service.RecipeRunService.SlotInput;
import ai.godance.web.dto.BatchDtos.BatchEstimate;
import ai.godance.web.dto.BatchDtos.BatchLimits;
import ai.godance.web.dto.BatchDtos.BatchRequest;
import ai.godance.web.dto.BatchDtos.BatchRowRequest;
import ai.godance.web.dto.BatchDtos.BatchRowView;
import ai.godance.web.dto.BatchDtos.BatchView;
import ai.godance.web.dto.RecipeDtos.RecipeRef;

/**
 * Recipe batches (round 12 brief 01): one version of a recipe run over many
 * rows. {@code /v1/recipe-batches/**}.
 *
 * <p><b>A batch charges nothing.</b> Estimating and creating one write no
 * ledger row and hold no credit. Each row becomes an ordinary job when
 * {@link RecipeBatchRunner} starts it, and that job's pre-debit and
 * settlement are the only charges there are.
 *
 * <p><b>Every row is checked before anything is written.</b> The request is
 * planned ({@link #plan}) the same way for an estimate and for a create:
 * each row goes through the code that checks a single recipe run
 * ({@link RecipeRuns}), and the first row that would be refused refuses the
 * whole request with 400 and its index. No batch exists after a refusal.
 *
 * <p><b>Private to their owner.</b> Another user's batch answers exactly as
 * one that does not exist: 404 {@code batch_not_found}.
 */
@Service
@EnableConfigurationProperties(BatchesProperties.class)
public class RecipeBatchService {

    /** Largest page {@code limit} accepts for batches and for rows, and the page sizes without one. */
    public static final int MAX_LIMIT = 100;
    public static final int DEFAULT_LIMIT = 20;
    public static final int DEFAULT_ROW_LIMIT = 50;

    /** First key of the per-user advisory lock; the second is the user id's hash. */
    static final int BATCHES_LOCK_SPACE = 7_000_036;

    /** A cursor after every batch: the first page. */
    private static final Instant END_OF_TIME = Instant.parse("9999-12-31T23:59:59Z");
    private static final UUID HIGHEST_UUID = new UUID(-1L, -1L);

    private final BatchesProperties props;
    private final RecipeBatchRepository batches;
    private final RecipeBatchRowRepository rows;
    private final RecipeRepository recipes;
    private final RecipeVersionRepository versions;
    private final UserRepository users;
    private final GenerationQuotes quotes;
    private final RecipeRuns runs;
    private final InputService inputs;
    private final WebhookDestinations webhookDestinations;
    private final AuditLogService audit;
    private final RecipeBatchViews views;
    private final RecipeBatchRunner runner;
    private final TransactionTemplate tx;

    public RecipeBatchService(BatchesProperties props, RecipeBatchRepository batches, RecipeBatchRowRepository rows,
                              RecipeRepository recipes, RecipeVersionRepository versions, UserRepository users,
                              GenerationQuotes quotes, RecipeRuns runs, InputService inputs,
                              WebhookDestinations webhookDestinations, AuditLogService audit,
                              RecipeBatchViews views, RecipeBatchRunner runner,
                              PlatformTransactionManager txManager) {
        this.props = props;
        this.batches = batches;
        this.rows = rows;
        this.recipes = recipes;
        this.versions = versions;
        this.users = users;
        this.quotes = quotes;
        this.runs = runs;
        this.inputs = inputs;
        this.webhookDestinations = webhookDestinations;
        this.audit = audit;
        this.views = views;
        this.runner = runner;
        this.tx = new TransactionTemplate(txManager);
    }

    // ===== Planning: what an estimate and a create have in common ======

    /** One row, checked: the value every variable has, and the input named for each slot. */
    private record PlannedRow(Map<String, String> variables, Map<String, Object> inputs) { }

    /** A batch request, checked in full and priced, with nothing written. */
    private record Plan(Recipe recipe, RecipeVersion version, String model, Map<String, Object> params,
                        List<PlannedRow> rows, long perRowCents, int maxConcurrency, String webhookUrl,
                        Set<UUID> fileIds) {
        long totalCents() {
            return Math.multiplyExact(perRowCents, (long) rows.size());
        }
    }

    /**
     * Checks a batch request and prices it. Writes nothing.
     *
     * <p>What concerns the whole batch is checked first and answers as it
     * would for a single run: the recipe version (404
     * {@code recipe_not_found}), the size ({@code batch_too_large}), the
     * model and settings (404, 400), and, for a recipe with input slots,
     * whether the account may use inputs and the model takes them. Then
     * every row, in order: its variables, an input for each slot, and each
     * input the caller's own. The first row refused answers 400 with its
     * index ({@link BatchRefusedException#row}).
     */
    private Plan plan(UUID userId, BatchRequest req) {
        if (req == null || req.versionId() == null) throw BatchRefusedException.invalid("version_id is required");
        RecipeVersion version = versions.findById(req.versionId()).orElseThrow(RecipeRefusedException::notFound);
        Recipe recipe = recipes.findById(version.getRecipeId())
                .filter(r -> r.getUserId().equals(userId))
                .orElseThrow(RecipeRefusedException::notFound);

        List<BatchRowRequest> given = req.rows() == null ? List.of() : req.rows();
        if (given.isEmpty()) throw BatchRefusedException.invalid("a batch needs at least one row");
        if (given.size() > props.maxRows()) throw BatchRefusedException.tooLarge(given.size(), props.maxRows());

        int concurrency = req.maxConcurrency() == null ? props.defaultConcurrency() : req.maxConcurrency();
        if (concurrency < 1 || concurrency > props.maxConcurrency()) {
            throw BatchRefusedException.invalid("max_concurrency must be between 1 and " + props.maxConcurrency());
        }
        String webhookUrl = req.webhookUrl() == null || req.webhookUrl().isBlank() ? null : req.webhookUrl();
        webhookDestinations.requireAllowed(webhookUrl);

        // Model and params default to the version's; a params that is sent replaces the version's as a whole.
        String model = req.model() == null || req.model().isBlank() ? version.getModel() : req.model().trim();
        Map<String, Object> params = new LinkedHashMap<>(req.params() != null ? req.params() : version.getParams());
        BatchSettings settings = BatchSettings.of(params);
        Modality modality = recipe.getModality();

        // The price of one row, from the code POST /v1/estimates uses and every job's pre-debit uses.
        // Every row has the same count of input images: one for each of the version's image slots.
        List<String> slotRoles = version.getInputs().stream().map(slot -> slot.get("role")).toList();
        int inputImages = (int) slotRoles.stream().filter("image"::equals).count();
        Quote quote = modality == Modality.IMAGE
                ? quotes.image(model, settings.n(), inputImages)
                : quotes.video(model, settings.n(), settings.seconds());

        if (!slotRoles.isEmpty()) {
            inputs.requireMayUse(userId);
            runs.requireModelTakesSlots(modality, quote.model(), slotRoles);
        }

        List<PlannedRow> planned = new ArrayList<>(given.size());
        Set<UUID> fileIds = new LinkedHashSet<>();
        for (int index = 0; index < given.size(); index++) {
            BatchRowRequest row = given.get(index);
            if (row == null) row = new BatchRowRequest(null, null);
            RecipeRef ref = new RecipeRef(version.getId(), row.variables(), row.inputs());
            RunPrompt run;
            try {
                run = modality == Modality.IMAGE
                        ? runs.checkImage(userId, model, ref)
                        : runs.checkVideo(userId, quote.model(), ref);
            } catch (ResponseStatusException refused) {
                throw BatchRefusedException.row(index, refused);
            }
            Map<String, Object> rowInputs = new LinkedHashMap<>();
            for (SlotInput input : run.inputs()) {
                rowInputs.put(input.slot(), BatchRowInputs.toStored(input.ref()));
                if (input.ref().fileId() != null) fileIds.add(input.ref().fileId());
            }
            planned.add(new PlannedRow(new LinkedHashMap<>(run.variables()), rowInputs));
        }
        return new Plan(recipe, version, model, params, planned, quote.costCents(), concurrency, webhookUrl, fileIds);
    }

    // ===== Estimate and create ========================================

    /**
     * What the batch would cost, and whether the balance covers it now.
     * The same checks as {@link #create}; nothing is written or charged.
     */
    @Transactional(readOnly = true)
    public BatchEstimate estimate(UUID userId, BatchRequest req) {
        Plan plan = plan(userId, req);
        long balance = users.findById(userId).map(User::getCreditBalanceCents)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "account not found"));
        long total = plan.totalCents();
        return new BatchEstimate(plan.rows().size(), total, balance, balance >= total, limits());
    }

    /** What the server allows a batch. */
    public BatchLimits limits() {
        return new BatchLimits(props.maxRows(), props.maxConcurrency());
    }

    /**
     * Creates a batch, QUEUED, with a PENDING row for each row of the
     * request. Charges nothing and starts nothing: the runner starts its
     * rows. {@code apiKeyId} is the key the caller authenticated with, or
     * null for a signed-in session; a key's batch runs its jobs against
     * that key's caps.
     *
     * @throws ResponseStatusException every refusal of {@link #plan}; 409
     *         {@code batch_limit_reached} when the account already has
     *         {@code godance.batches.max-unfinished-per-account} unfinished batches
     */
    @Transactional
    public BatchView create(UUID userId, UUID apiKeyId, BatchRequest req, String ip) {
        return create(userId, apiKeyId, req, ip, null);
    }

    /**
     * {@link #create(UUID, UUID, BatchRequest, String)} with the most the whole
     * batch may cost (round 13, the MCP tool {@code start_batch}). A batch
     * whose estimate is above it is refused with {@link MaxCostExceededException}
     * and nothing is written. The ceiling is kept on the batch: each row's job
     * is then priced against what is left of it ({@link RecipeBatchRunner}), so
     * a price that rises while the batch runs cannot take it past the ceiling.
     * Null is no ceiling.
     */
    @Transactional
    public BatchView create(UUID userId, UUID apiKeyId, BatchRequest req, String ip, Long maxCostCents) {
        Plan plan = plan(userId, req);
        MaxCostExceededException.requireWithin(plan.totalCents(), maxCostCents);

        // Counted and written one request at a time, so two cannot both take the last place.
        batches.lockBatchesOf(BATCHES_LOCK_SPACE, userId.hashCode());
        if (batches.countUnfinished(userId) >= props.maxUnfinishedPerAccount()) {
            throw BatchRefusedException.limitReached(props.maxUnfinishedPerAccount());
        }

        RecipeBatch batch = new RecipeBatch();
        batch.setUserId(userId);
        batch.setCreatedByChannel(apiKeyId != null ? JobChannel.API : JobChannel.STUDIO);
        batch.setApiKeyId(apiKeyId);
        batch.setRecipeId(plan.recipe().getId());
        batch.setRecipeVersionId(plan.version().getId());
        batch.setModality(plan.recipe().getModality());
        batch.setModel(plan.model());
        batch.setParams(plan.params());
        batch.setVariableNames(plan.version().getVariables().stream().map(v -> v.get("name")).toList());
        batch.setMaxConcurrency(plan.maxConcurrency());
        batch.setWebhookUrl(plan.webhookUrl());
        batch.setRowsTotal(plan.rows().size());
        batch.setEstimatedTotalCents(plan.totalCents());
        batch.setMaxCostCents(maxCostCents);
        batches.save(batch);
        UUID batchId = batch.getId();

        List<RecipeBatchRow> toSave = new ArrayList<>(plan.rows().size());
        for (int index = 0; index < plan.rows().size(); index++) {
            PlannedRow row = plan.rows().get(index);
            toSave.add(new RecipeBatchRow(batchId, index, row.variables(), row.inputs()));
        }
        rows.saveAll(toSave);

        // The files the rows name are in use from now, until the batch ends.
        inputs.holdForBatch(batchId, userId, plan.fileIds());

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("batchId", batchId.toString());
        detail.put("recipeId", plan.recipe().getId().toString());
        detail.put("rows", plan.rows().size());
        detail.put("estimatedTotalCents", plan.totalCents());
        if (maxCostCents != null) detail.put("maxCostCents", maxCostCents);
        detail.put("channel", batch.getCreatedByChannel().name());
        audit.record(AuditLogService.EVT_BATCH_CREATED, AuditOutcome.SUCCESS, userId, ip, detail);
        return views.view(requireOwned(userId, batchId));
    }

    // ===== Read =======================================================

    /** One of the caller's batches; 404 {@code batch_not_found} otherwise. */
    @Transactional(readOnly = true)
    public BatchView get(UUID userId, UUID batchId) {
        return views.view(requireOwned(userId, batchId));
    }

    /**
     * The caller's batches, newest first. {@code before} is one of the
     * caller's batch ids (400 otherwise) and returns only older batches;
     * {@code recipeId} keeps only the batches of that recipe.
     */
    @Transactional(readOnly = true)
    public List<BatchView> list(UUID userId, Integer limit, UUID before, UUID recipeId) {
        int pageSize = limit == null ? DEFAULT_LIMIT : limit;
        if (pageSize < 1 || pageSize > MAX_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and " + MAX_LIMIT);
        }
        Instant beforeCreatedAt = END_OF_TIME;
        UUID beforeId = HIGHEST_UUID;
        if (before != null) {
            RecipeBatch cursor = batches.findByIdAndUserId(before, userId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "before must be the id of one of your batches"));
            beforeCreatedAt = cursor.getCreatedAt();
            beforeId = cursor.getId();
        }
        return views.views(batches.findPageForUser(userId, recipeId == null ? "" : recipeId.toString(),
                beforeCreatedAt, beforeId, pageSize));
    }

    /**
     * A page of a batch's rows, in row order. {@code after} is the index of
     * the last row already seen; null starts at the first row.
     */
    @Transactional(readOnly = true)
    public List<BatchRowView> rows(UUID userId, UUID batchId, Integer limit, Integer after) {
        requireOwned(userId, batchId);
        int pageSize = limit == null ? DEFAULT_ROW_LIMIT : limit;
        if (pageSize < 1 || pageSize > MAX_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and " + MAX_LIMIT);
        }
        return views.rowViews(rows.findPage(batchId, after == null ? -1 : after, pageSize));
    }

    // ===== Cancel and resume ==========================================

    /**
     * Cancels the caller's batch: every row that had not started is
     * SKIPPED, and the rows that are running finish and are charged as any
     * job is. Cancelling a batch that has already ended changes nothing.
     */
    public BatchView cancel(UUID userId, UUID batchId, String ip) {
        tx.executeWithoutResult(status -> {
            requireOwned(userId, batchId);
            if (batches.cancel(batchId) == 0) return;
            int skipped = rows.skipPending(batchId, Instant.now());
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("batchId", batchId.toString());
            detail.put("reason", "requested");
            detail.put("rowsSkipped", skipped);
            audit.record(AuditLogService.EVT_BATCH_CANCELLED, AuditOutcome.SUCCESS, userId, ip, detail);
        });
        // With no row running the batch is over now; otherwise the runner ends it when the last one finishes.
        runner.settle(batchId);
        return get(userId, batchId);
    }

    /**
     * Resumes the caller's paused batch: the runner starts its rows again
     * from the first one still pending.
     *
     * @throws BatchRefusedException 409 {@code batch_not_resumable} for a batch that is not paused
     */
    @Transactional
    public BatchView resume(UUID userId, UUID batchId) {
        RecipeBatch batch = requireOwned(userId, batchId);
        if (batches.resume(batchId, userId) == 0) {
            throw BatchRefusedException.notResumable(batch.getStatus().name());
        }
        return views.view(requireOwned(userId, batchId));
    }

    RecipeBatch requireOwned(UUID userId, UUID batchId) {
        return batches.findByIdAndUserId(batchId, userId).orElseThrow(BatchRefusedException::notFound);
    }
}
