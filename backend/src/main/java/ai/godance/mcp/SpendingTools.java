package ai.godance.mcp;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import io.modelcontextprotocol.server.McpStatelessServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import ai.godance.domain.Modality;
import ai.godance.gateway.ImageDtos.ImageRequest;
import ai.godance.gateway.VideoDtos.VideoRequest;
import ai.godance.service.GenerationQuotes;
import ai.godance.service.ImageEdits;
import ai.godance.service.ImageGenerationService;
import ai.godance.service.ImageGenerationService.ImageResult;
import ai.godance.service.ImageResponseFormat;
import ai.godance.service.InputService.ResolvedInput;
import ai.godance.service.JobOrigin;
import ai.godance.service.RecipeBatchService;
import ai.godance.service.RecipeRefusedException;
import ai.godance.service.RecipeRunService;
import ai.godance.service.RecipeRunService.RunPrompt;
import ai.godance.service.RecipeRuns;
import ai.godance.service.RecipeRuns.ImageRun;
import ai.godance.service.RecipeRuns.VideoRun;
import ai.godance.service.SpendingLimitService;
import ai.godance.service.VideoGenerationService;
import ai.godance.service.VideoInputs;
import ai.godance.web.ImageJobAnswers;
import ai.godance.web.dto.BatchDtos.BatchRequest;
import ai.godance.web.dto.BatchDtos.BatchView;
import ai.godance.web.dto.InputDtos.InputRef;
import ai.godance.web.dto.RecipeDtos.RecipeRef;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * The tools that spend: {@code generate_image}, {@code generate_video},
 * {@code run_recipe} and {@code start_batch}.
 *
 * <p>Each does what its REST route does, through the same services, in the
 * same order: the caller's spending caps first (what
 * {@code SpendingLimitFilter} checks before a generation route), then the
 * request's checks, the recipe rendered and the inputs loaded, then the job.
 * The pre-debit, the cap reservation, the regional allowlist, the refund of
 * a failed job and {@code channel: API} all come from the services.
 *
 * <p>{@code max_cost_cents} is handed to the service that takes the
 * pre-debit, and checked there against the price it is about to charge: a
 * request above it is refused with {@code max_cost_exceeded} and nothing is
 * charged or written. A job is never charged more than its pre-debit
 * ({@code GenerationJobSettlement}), so it never costs more than the
 * ceiling. A batch keeps its ceiling, and each row's job gets what is left.
 *
 * <p>Results carry signed URLs, never file bytes: an image request is always
 * answered with links ({@code response_format} {@code url}).
 */
@Component
class SpendingTools implements McpToolProvider {

    private final ToolSupport support;
    private final SpendingLimitService spendingLimits;
    private final ImageGenerationService images;
    private final VideoGenerationService videos;
    private final ImageEdits edits;
    private final ImageJobAnswers answers;
    private final RecipeRuns runs;
    private final RecipeRunService recipeRuns;
    private final RecipePlans plans;
    private final GenerationQuotes quotes;
    private final RecipeBatchService batches;
    private final ObjectMapper json;

    SpendingTools(ToolSupport support, SpendingLimitService spendingLimits, ImageGenerationService images,
                  VideoGenerationService videos, ImageEdits edits, ImageJobAnswers answers, RecipeRuns runs,
                  RecipeRunService recipeRuns, RecipePlans plans, GenerationQuotes quotes,
                  RecipeBatchService batches, ObjectMapper json) {
        this.support = support;
        this.spendingLimits = spendingLimits;
        this.images = images;
        this.videos = videos;
        this.edits = edits;
        this.answers = answers;
        this.runs = runs;
        this.recipeRuns = recipeRuns;
        this.plans = plans;
        this.quotes = quotes;
        this.batches = batches;
        this.json = json;
    }

    @Override
    public List<AsyncToolSpecification> tools() {
        return List.of(
                support.spec(support.acting("generate_image", "Generate or edit images", IMAGE_DESCRIPTION,
                        IMAGE_INPUT, ToolSupport.JOB_OUTPUT), this::generateImage),
                support.spec(support.acting("generate_video", "Generate a video", VIDEO_DESCRIPTION,
                        VIDEO_INPUT, ToolSupport.JOB_OUTPUT), this::generateVideo),
                support.spec(support.acting("run_recipe", "Run one of your recipes", RECIPE_DESCRIPTION,
                        RECIPE_INPUT, ToolSupport.JOB_OUTPUT), this::runRecipe),
                support.spec(support.acting("start_batch", "Start a recipe batch", BATCH_DESCRIPTION,
                        BATCH_INPUT, BATCH_OUTPUT), this::startBatch));
    }

    /** A job that was made, or an image request that ran to its end inside the call. */
    private record Started(UUID jobId, ImageResult result, long estimatedCents) { }

    // ===== generate_image =============================================

    private Mono<CallToolResult> generateImage(McpCaller caller, ToolArgs args) {
        String model = args.requiredText("model");
        String prompt = args.requiredText("prompt");
        Integer n = args.integerAtLeast("n", 1);
        String size = args.text("size");
        String quality = args.text("quality");
        String style = args.text("style");
        List<InputRef> given = args.listOf("images", InputRef.class, json);
        InputRef mask = args.as("mask", InputRef.class, json);
        String galleryRecipe = args.text("gallery_recipe");
        Boolean async = args.bool("async");
        Long max = args.maxCostCents();
        boolean edit = !given.isEmpty() || mask != null;
        // As /v1/images/edits: while inputs are off, an edit answers inputs_disabled before anything else.
        if (edit) edits.requireEnabled();

        // An edit takes no style, as /v1/images/edits has none.
        ImageRequest domain = new ImageRequest(model, prompt, size, n, edit ? null : style, quality);
        boolean runsAsJob = images.requiresAsyncFor(domain);
        boolean asJob = Boolean.TRUE.equals(async) || runsAsJob;
        boolean mayWait = answers.mayWait(caller.apiKeyId(), runsAsJob, async, null);
        return ToolSupport.blocking(() -> {
            spendingLimits.enforce(caller.userId(), caller.apiKeyId());
            // The channel comes from the key; a malformed gallery_recipe is a 400 before any charge.
            JobOrigin origin = JobOrigin.of(caller.apiKeyId(), galleryRecipe);
            ImageRequest request;
            List<ResolvedInput> inputs;
            JobOrigin from;
            if (edit) {
                RunPrompt run = recipeRuns.resolve(caller.userId(), Modality.IMAGE, prompt, null);
                inputs = edits.resolve(caller.userId(), model, new ImageEdits.Refs(given, mask));
                request = edits.attach(domain.withPrompt(run.prompt()), inputs);
                from = run.origin(origin);
            } else {
                ImageRun run = runs.image(caller.userId(), domain, prompt, null, origin);
                request = run.request();
                inputs = run.inputs();
                from = run.origin();
            }
            return startImage(caller, request, inputs, from, asJob, max);
        }).flatMap(started -> imageAnswer(caller, started, model, mayWait))
                .map(out -> support.results().success(summary(out), out));
    }

    // ===== generate_video =============================================

    private Mono<CallToolResult> generateVideo(McpCaller caller, ToolArgs args) {
        String model = args.requiredText("model");
        String prompt = args.requiredText("prompt");
        String size = args.text("size");
        Integer seconds = args.integerAtLeast("seconds", 1);
        Integer n = args.integerAtLeast("n", 1);
        InputRef first = args.as("first_frame", InputRef.class, json);
        InputRef last = args.as("last_frame", InputRef.class, json);
        List<InputRef> references = args.listOf("reference_images", InputRef.class, json);
        String galleryRecipe = args.text("gallery_recipe");
        Long max = args.maxCostCents();
        return ToolSupport.blocking(() -> {
            spendingLimits.enforce(caller.userId(), caller.apiKeyId());
            JobOrigin origin = JobOrigin.of(caller.apiKeyId(), galleryRecipe);
            VideoRequest domain = new VideoRequest(model, prompt, size, seconds, n);
            VideoInputs.Refs inputs = new VideoInputs.Refs(first, last, references.isEmpty() ? null : references);
            VideoRun run = runs.video(caller.userId(), domain, prompt, null, inputs, origin);
            UUID jobId = videos.submit(caller.userId(), caller.apiKeyId(), run.request(), null, run.origin(),
                    run.inputs(), max);
            return queued(jobId, quotes.video(model, n, seconds).costCents());
        }).map(out -> support.results().success(summary(out), out));
    }

    // ===== run_recipe =================================================

    private Mono<CallToolResult> runRecipe(McpCaller caller, ToolArgs args) {
        UUID recipeId = args.id("recipe_id", RecipeRefusedException::notFound);
        if (recipeId == null) throw ToolArgs.badRequest("recipe_id is required");
        UUID versionId = args.id("version_id", RecipeRefusedException::notFound);
        Map<String, String> variables = stringMap(args, "variables");
        Map<String, InputRef> inputs = inputMap(args, "inputs");
        Boolean async = args.bool("async");
        Long max = args.maxCostCents();
        return ToolSupport.blocking(() -> {
            spendingLimits.enforce(caller.userId(), caller.apiKeyId());
            RecipePlans.Plan plan = plans.plan(caller.userId(), recipeId, versionId, args);
            RecipeRef recipe = new RecipeRef(plan.version().id(), variables, inputs);
            JobOrigin origin = JobOrigin.of(caller.apiKeyId(), null);
            if (plan.modality() == Modality.VIDEO) {
                VideoRequest domain = new VideoRequest(plan.model(), null, plan.size(), plan.seconds(), plan.n());
                VideoRun run = runs.video(caller.userId(), domain, null, recipe, VideoInputs.Refs.NONE, origin);
                UUID jobId = videos.submit(caller.userId(), caller.apiKeyId(), run.request(), null, run.origin(),
                        run.inputs(), max);
                return Mono.just(withRecipe(queued(jobId, plans.quote(plan).costCents()), plan));
            }
            // A recipe with input slots runs as an edit and one without as a generation, as on REST.
            ImageRequest domain = new ImageRequest(plan.model(), null, plan.size(), plan.n(), plan.style(),
                    plan.quality());
            boolean runsAsJob = images.requiresAsyncFor(domain);
            boolean mayWait = answers.mayWait(caller.apiKeyId(), runsAsJob, async, null);
            ImageRun run = runs.image(caller.userId(), domain, null, recipe, origin);
            Started started = startImage(caller, run.request(), run.inputs(), run.origin(),
                    Boolean.TRUE.equals(async) || runsAsJob, max);
            return imageAnswer(caller, started, plan.model(), mayWait).map(out -> withRecipe(out, plan));
        }).flatMap(answer -> answer).map(out -> support.results().success(summary(out), out));
    }

    private static Map<String, Object> withRecipe(Map<String, Object> out, RecipePlans.Plan plan) {
        out.put("recipe_id", plan.recipe().id());
        out.put("version_id", plan.version().id());
        return out;
    }

    // ===== start_batch ================================================

    private Mono<CallToolResult> startBatch(McpCaller caller, ToolArgs args) {
        Long max = args.maxCostCents();
        return ToolSupport.blocking(() -> {
            BatchView batch = batches.create(caller.userId(), caller.apiKeyId(), batchRequest(args), caller.clientIp(), max);
            Map<String, Object> out = support.results().toMap(batch);
            out.put("max_cost_cents", max);
            String limit = max == null ? "" : ", and no row starts that would take it past " + max + " cents";
            return support.results().success("Batch " + batch.id() + " is " + batch.status() + " with "
                    + batch.rowsTotal() + " rows, estimated at " + batch.estimatedTotalCents() + " cents in all"
                    + limit + ". Its rows start a few at a time; follow it with get_batch.", out);
        });
    }

    /**
     * The REST request a start_batch call is: its arguments without
     * max_cost_cents, and no webhook. A missing version_id or rows is left to
     * the service, which refuses them as {@code POST /v1/recipe-batches} does.
     */
    private BatchRequest batchRequest(ToolArgs args) {
        UUID versionId = args.id("version_id", () -> ToolArgs.badRequest("version_id must be a recipe version id"));
        Map<String, Object> params = args.has("params") ? objectMap(args, "params") : null;
        List<ai.godance.web.dto.BatchDtos.BatchRowRequest> rows =
                args.listOf("rows", ai.godance.web.dto.BatchDtos.BatchRowRequest.class, json);
        return new BatchRequest(versionId, args.text("model"), params, rows, args.integer("max_concurrency"), null,
                null);
    }

    // ===== The shared image path ======================================

    /**
     * An image request's job, or its result when it runs inside the call. The
     * ceiling is checked by the service, against the price it charges; the
     * price is read again afterwards only to report it.
     */
    private Started startImage(McpCaller caller, ImageRequest request, List<ResolvedInput> inputs, JobOrigin origin,
                               boolean asJob, Long max) {
        UUID jobId = null;
        ImageResult result = null;
        if (asJob) {
            jobId = images.executeAsync(caller.userId(), caller.apiKeyId(), request, null, origin, inputs, max);
        } else {
            result = images.execute(caller.userId(), caller.apiKeyId(), request, origin, inputs, max);
        }
        long estimate = quotes.image(request.model(), request.n(), request.images().size()).costCents();
        return new Started(jobId, result, estimate);
    }

    /**
     * What an image request answers, by round 10's rule for the key
     * ({@link ImageJobAnswers}): the job at once, or, for a key set to wait,
     * the finished images; links only.
     */
    private Mono<Map<String, Object>> imageAnswer(McpCaller caller, Started started, String model, boolean mayWait) {
        Mono<ResponseEntity<?>> answer = started.jobId() != null
                ? answers.forJob(caller.apiKeyId(), started.jobId(), ImageResponseFormat.URL, mayWait)
                : Mono.just(answers.forResult(started.result(), ImageResponseFormat.URL, model));
        return answer.map(response -> {
            Map<String, Object> body = support.results().toMap(response.getBody());
            Map<String, Object> out = new LinkedHashMap<>();
            if (body.containsKey("data")) {
                out.put("job_id", body.get("job_id") != null ? body.get("job_id") : started.jobId());
                out.put("status", "DONE");
                if (body.get("cost_cents") != null) out.put("cost_cents", body.get("cost_cents"));
                out.put("images", body.get("data"));
            } else {
                out.put("job_id", body.get("job_id"));
                out.put("status", body.get("status"));
                out.put("poll_url", body.get("poll_url"));
            }
            out.put("estimated_cost_cents", started.estimatedCents());
            return out;
        });
    }

    private static Map<String, Object> queued(UUID jobId, long estimate) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("job_id", jobId.toString());
        out.put("status", "QUEUED");
        out.put("poll_url", "/v1/jobs/" + jobId);
        out.put("estimated_cost_cents", estimate);
        return out;
    }

    private static String summary(Map<String, Object> out) {
        if ("DONE".equals(out.get("status"))) {
            return "Done: job " + out.get("job_id") + " made " + ((List<?>) out.get("images")).size()
                    + " image(s); the links in images are signed and expire. Charged "
                    + (out.get("cost_cents") != null ? out.get("cost_cents") : out.get("estimated_cost_cents"))
                    + " cents at most.";
        }
        return "Job " + out.get("job_id") + " is " + out.get("status") + "; " + out.get("estimated_cost_cents")
                + " cents are held for it and it is charged no more. Call get_job with this job_id until its "
                + "status is DONE or FAILED (a failed job is refunded).";
    }

    // ===== Argument shapes ============================================

    private Map<String, String> stringMap(ToolArgs args, String name) {
        Map<String, Object> raw = objectMap(args, name);
        if (raw == null) return null;
        Map<String, String> values = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (value != null && !(value instanceof String || value instanceof Number || value instanceof Boolean)) {
                throw ToolArgs.badRequest(name + "." + key + " must be text");
            }
            values.put(key, value == null ? null : String.valueOf(value));
        });
        return values;
    }

    private Map<String, InputRef> inputMap(ToolArgs args, String name) {
        Map<String, Object> raw = objectMap(args, name);
        if (raw == null) return null;
        Map<String, InputRef> refs = new LinkedHashMap<>();
        raw.forEach((slot, value) -> {
            try {
                refs.put(slot, json.convertValue(value, InputRef.class));
            } catch (RuntimeException e) {
                throw ToolArgs.badRequest(name + "." + slot + " must be {\"file_id\": …} or {\"job_output\": …}");
            }
        });
        return refs;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(ToolArgs args, String name) {
        Object value = args.as(name, Map.class, json);
        if (value == null) return null;
        return new HashMap<>((Map<String, Object>) value);
    }

    // ===== Definitions ================================================

    static final String IMAGE_DESCRIPTION = """
            Make images from a prompt, or edit images: give images (and optionally mask) as input references \
            to edit them with the prompt. Spends credit: the model's price per image times n, plus its price \
            per input image for an edit. Call estimate_cost first (with input_images for an edit) and pass \
            the price as max_cost_cents: above it the tool refuses and nothing is charged. Answers with a \
            job: when the API key is set to wait for images, the finished images; otherwise a job_id to \
            follow with get_job. Images come back as signed links that expire. To run a gallery recipe's \
            prompt, set gallery_recipe to its slug.""";

    static final String IMAGE_INPUT = """
            {"type": "object",
             "properties": {
               "model": {"type": "string", "description": "An image model id from list_models."},
               "prompt": {"type": "string", "description": "What to make, or what to change in the input images."},
               "n": {"type": "integer", "minimum": 1, "description": "How many images; 1 by default. At most the model's max_n."},
               "size": {"type": "string", "description": "One of the model's sizes, e.g. 1024x1024."},
               "quality": {"type": "string"},
               "style": {"type": "string", "description": "For a generation; an edit takes none."},
               "images": {"type": "array", "items": %1$s, "description": "Images to edit, in order."},
               "mask": %1$s,
               "gallery_recipe": {"type": "string", "description": "The slug of the gallery recipe this request runs, if any."},
               "async": {"type": "boolean", "description": "true: answer with the job at once, even for a key set to wait for images."},
               "max_cost_cents": %2$s
             },
             "required": ["model", "prompt"],
             "additionalProperties": false}""".formatted(ToolSupport.INPUT_REF, ToolSupport.MAX_COST);

    static final String VIDEO_DESCRIPTION = """
            Make a video from a prompt, optionally starting from first_frame (and ending on last_frame) or \
            guided by reference_images: input references from upload_input or an earlier image job. Spends \
            credit: the model's price per second times seconds times n. Call estimate_cost first and pass \
            the price as max_cost_cents: above it the tool refuses and nothing is charged. Always answers \
            with a job_id: call get_job every 10 to 30 seconds until its status is DONE or FAILED.""";

    static final String VIDEO_INPUT = """
            {"type": "object",
             "properties": {
               "model": {"type": "string", "description": "A video model id from list_models."},
               "prompt": {"type": "string"},
               "size": {"type": "string"},
               "seconds": {"type": "integer", "minimum": 1, "description": "The length; the model's default when left out."},
               "n": {"type": "integer", "minimum": 1},
               "first_frame": %1$s,
               "last_frame": %1$s,
               "reference_images": {"type": "array", "items": %1$s},
               "gallery_recipe": {"type": "string", "description": "The slug of the gallery recipe this request runs, if any."},
               "max_cost_cents": %2$s
             },
             "required": ["model", "prompt"],
             "additionalProperties": false}""".formatted(ToolSupport.INPUT_REF, ToolSupport.MAX_COST);

    static final String RECIPE_DESCRIPTION = """
            Run one of the caller's saved recipes: Godance fills its template with variables (the others \
            keep their defaults), places inputs in its input slots, and runs it with the recipe version's \
            model and settings unless model, n, size, seconds, quality or style are given. Spends credit \
            like generate_image or generate_video. Call estimate_cost with recipe_id first and pass the \
            price as max_cost_cents: above it the tool refuses and nothing is charged. Answers with a job_id \
            to follow with get_job (or, for an image recipe and a key set to wait, the finished images).""";

    static final String RECIPE_INPUT = """
            {"type": "object",
             "properties": {
               "recipe_id": {"type": "string", "description": "One of the caller's recipe ids (search_recipes, get_recipe)."},
               "version_id": {"type": "string", "description": "A version of that recipe; the latest when left out."},
               "variables": {"type": "object", "additionalProperties": {"type": "string"}, "description": "A value for each variable to set."},
               "inputs": {"type": "object", "additionalProperties": %1$s, "description": "An input reference for each input slot, by slot name."},
               "model": {"type": "string"},
               "n": {"type": "integer", "minimum": 1},
               "size": {"type": "string"},
               "seconds": {"type": "integer", "minimum": 1},
               "quality": {"type": "string"},
               "style": {"type": "string"},
               "async": {"type": "boolean", "description": "true: answer with the job at once, even for a key set to wait for images."},
               "max_cost_cents": %2$s
             },
             "required": ["recipe_id"],
             "additionalProperties": false}""".formatted(ToolSupport.INPUT_REF, ToolSupport.MAX_COST);

    static final String BATCH_DESCRIPTION = """
            Run one version of one of the caller's recipes over many rows, each row with its own variables \
            and inputs; each row becomes an ordinary job, started a few at a time. Starting charges nothing; \
            each row's job is charged when it starts. Call estimate_cost with batch first and pass the total \
            as max_cost_cents: a batch above it is refused, and while it runs no row starts that would take \
            the batch past it. When credit or a spending cap runs out, the batch pauses. Follow it with \
            get_batch.""";

    static final String BATCH_INPUT = """
            {"type": "object",
             "properties": {
               "version_id": {"type": "string", "description": "A version of one of the caller's recipes."},
               "rows": {"type": "array", "minItems": 1, "items": {"type": "object",
                 "properties": {
                   "variables": {"type": "object", "additionalProperties": {"type": "string"}},
                   "inputs": {"type": "object", "additionalProperties": %1$s}
                 },
                 "additionalProperties": false}},
               "model": {"type": "string", "description": "The version's model when left out."},
               "params": {"type": "object", "description": "size, n, seconds, style, quality; replaces the version's as a whole."},
               "max_concurrency": {"type": "integer", "minimum": 1},
               "max_cost_cents": %2$s
             },
             "required": ["version_id", "rows"],
             "additionalProperties": false}""".formatted(ToolSupport.INPUT_REF, ToolSupport.MAX_COST);

    static final String BATCH_OUTPUT = """
            {"type": "object",
             "properties": {
               "id": {"type": "string", "description": "The batch id: follow it with get_batch."},
               "status": {"type": "string"},
               "rows_total": {"type": "integer"},
               "estimated_total_cents": {"type": "integer"}
             },
             "required": ["id", "status"]}""";
}
