package ai.godance.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import io.modelcontextprotocol.server.McpStatelessServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import ai.godance.service.GenerationQuotes;
import ai.godance.service.GenerationQuotes.Quote;
import ai.godance.service.JobReadService;
import ai.godance.service.RecipeBatchService;
import ai.godance.service.RecipeRefusedException;
import ai.godance.service.RecipeService;
import ai.godance.web.ModelsController;
import ai.godance.web.dto.BatchDtos.BatchEstimate;
import ai.godance.web.dto.BatchDtos.BatchRequest;
import ai.godance.web.dto.RecipeDtos.RecipeView;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * The tools that find and price: {@code search_recipes}, {@code get_recipe},
 * {@code list_models} and {@code estimate_cost}. None of them charges or
 * changes anything.
 */
@Component
class DiscoveryTools implements McpToolProvider {

    private static final String CURRENCY = "USD";
    private static final int RECENT_RUNS = 5;

    private final ToolSupport support;
    private final GalleryIndex gallery;
    private final RecipeService recipes;
    private final RecipePlans plans;
    private final JobReadService jobs;
    private final ModelsController models;
    private final GenerationQuotes quotes;
    private final RecipeBatchService batches;
    private final ObjectMapper json;

    DiscoveryTools(ToolSupport support, GalleryIndex gallery, RecipeService recipes, RecipePlans plans,
                   JobReadService jobs, ModelsController models, GenerationQuotes quotes,
                   RecipeBatchService batches, ObjectMapper json) {
        this.support = support;
        this.gallery = gallery;
        this.recipes = recipes;
        this.plans = plans;
        this.jobs = jobs;
        this.models = models;
        this.quotes = quotes;
        this.batches = batches;
        this.json = json;
    }

    @Override
    public List<AsyncToolSpecification> tools() {
        return List.of(
                support.spec(support.readOnly("search_recipes", "Search recipes", SEARCH_DESCRIPTION,
                        SEARCH_INPUT, SEARCH_OUTPUT), this::search),
                support.spec(support.readOnly("get_recipe", "Get a recipe", GET_DESCRIPTION,
                        GET_INPUT, GET_OUTPUT), this::get),
                support.spec(support.readOnly("list_models", "List models", MODELS_DESCRIPTION,
                        MODELS_INPUT, MODELS_OUTPUT), this::listModels),
                support.spec(support.readOnly("estimate_cost", "Estimate a cost", ESTIMATE_DESCRIPTION,
                        ESTIMATE_INPUT, ESTIMATE_OUTPUT), this::estimate));
    }

    // ===== search_recipes =============================================

    private Mono<CallToolResult> search(McpCaller caller, ToolArgs args) {
        List<String> terms = terms(args.text("query"));
        String modality = modality(args.text("modality"));
        String source = args.text("source") == null ? "all" : args.text("source");
        if (!List.of("all", "gallery", "mine").contains(source)) {
            throw ToolArgs.badRequest("source must be all, gallery or mine");
        }
        Integer requested = args.integer("limit");
        int limit = requested == null ? 10 : requested;
        if (limit < 1 || limit > 50) throw ToolArgs.badRequest("limit must be between 1 and 50");

        Mono<List<Map<String, Object>>> mine = "gallery".equals(source) ? Mono.just(List.of())
                : ToolSupport.blocking(() -> recipes.list(caller.userId(), RecipeService.MAX_LIMIT, null))
                        .map(found -> matchMine(found, terms, modality));
        boolean searchGallery = !"mine".equals(source);
        Mono<GalleryIndex.Snapshot> index = searchGallery ? gallery.index()
                : Mono.just(new GalleryIndex.Snapshot(false, List.of(), Map.of(), java.time.Instant.MAX));
        return mine.zipWith(index).map(both -> {
            List<Map<String, Object>> results = new ArrayList<>(both.getT1());
            results.addAll(matchGallery(both.getT2(), terms, modality));
            List<Map<String, Object>> page = results.size() > limit ? results.subList(0, limit) : results;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("results", page);
            if (searchGallery) out.put("gallery_available", both.getT2().available());
            boolean galleryMissing = searchGallery && !both.getT2().available();
            String summary = page.size() + (page.size() == 1 ? " recipe" : " recipes") + " found"
                    + (galleryMissing ? "; the public gallery is not available now, so only your own recipes were searched" : "")
                    + ". Next: get_recipe with a result's slug (gallery) or id (yours).";
            return support.results().success(summary, out);
        });
    }

    private List<Map<String, Object>> matchMine(List<RecipeView> found, List<String> terms, String modality) {
        List<Map<String, Object>> matches = new ArrayList<>();
        for (RecipeView recipe : found) {
            String kind = recipe.modality().name().toLowerCase(Locale.ROOT);
            if (modality != null && !modality.equals(kind)) continue;
            String haystack = String.join("\n", recipe.title(), recipe.latestVersion().template(),
                    String.valueOf(recipe.latestVersion().model())).toLowerCase(Locale.ROOT);
            if (!terms.stream().allMatch(haystack::contains)) continue;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("source", "mine");
            result.put("id", recipe.id());
            result.put("title", recipe.title());
            result.put("modality", kind);
            result.put("model", recipe.latestVersion().model());
            result.put("version_id", recipe.latestVersion().id());
            result.put("variables", recipe.latestVersion().variables().stream().map(v -> v.name()).toList());
            result.put("input_slots", recipe.latestVersion().inputs().size());
            matches.add(support.results().toMap(result));
        }
        return matches;
    }

    private List<Map<String, Object>> matchGallery(GalleryIndex.Snapshot index, List<String> terms, String modality) {
        List<Map<String, Object>> matches = new ArrayList<>();
        for (Map<String, Object> entry : index.recipes()) {
            String slug = (String) entry.get("slug");
            if (modality != null && !modality.equals(entry.get("modality"))) continue;
            String haystack = String.join("\n", slug, String.valueOf(entry.get("title")),
                    String.valueOf(entry.get("tags")), String.valueOf(entry.get("model")),
                    index.searchText().getOrDefault(slug, "")).toLowerCase(Locale.ROOT);
            if (!terms.stream().allMatch(haystack::contains)) continue;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("source", "gallery");
            result.putAll(entry);
            result.put("url", gallery.pageUrl(slug));
            matches.add(result);
        }
        return matches;
    }

    private static List<String> terms(String query) {
        if (query == null) return List.of();
        if (query.length() > 200) throw ToolArgs.badRequest("query must be at most 200 characters");
        return List.of(query.toLowerCase(Locale.ROOT).trim().split("\\s+")).stream()
                .filter(term -> !term.isBlank()).toList();
    }

    private static String modality(String raw) {
        if (raw == null) return null;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (!value.equals("image") && !value.equals("video")) throw ToolArgs.badRequest("modality must be image or video");
        return value;
    }

    // ===== get_recipe =================================================

    private Mono<CallToolResult> get(McpCaller caller, ToolArgs args) {
        String slug = args.text("slug");
        boolean byId = args.has("id");
        if ((slug == null) == !byId) throw ToolArgs.badRequest("give exactly one of slug (a gallery recipe) or id (one of yours)");
        if (slug != null) {
            return gallery.recipe(slug.trim()).map(found -> {
                Map<String, Object> recipe = found.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no published gallery recipe has the slug " + slug.trim()));
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("source", "gallery");
                out.put("url", gallery.pageUrl(slug.trim()));
                out.putAll(recipe);
                return support.results().success("Gallery recipe " + slug.trim() + ". To run its prompt, use "
                        + "generate_image or generate_video with that prompt and model, and gallery_recipe set to "
                        + "its slug. To price it first, use estimate_cost.", out);
            });
        }
        UUID id = args.id("id", RecipeRefusedException::notFound);
        return ToolSupport.blocking(() -> {
            RecipeView recipe = recipes.get(caller.userId(), id);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("source", "mine");
            out.put("recipe", recipe);
            try {
                Quote quote = plans.quote(plans.plan(caller.userId(), id, null, new ToolArgs(Map.of())));
                out.put("estimated_cost_cents", quote.costCents());
                out.put("currency", CURRENCY);
            } catch (ResponseStatusException unpriced) {
                // A model withdrawn since the recipe was saved: the recipe is still shown, with why it can't be priced.
                out.put("estimate_unavailable", unpriced.getReason());
            }
            out.put("recent_runs", jobs.list(caller.userId(), RECENT_RUNS, null, null, null, null, id));
            String price = out.containsKey("estimated_cost_cents")
                    ? " One run of its latest version costs " + out.get("estimated_cost_cents") + " cents."
                    : "";
            return support.results().success("Your recipe \"" + recipe.title() + "\"." + price
                    + " Run it with run_recipe (recipe_id " + id + ").", out);
        });
    }

    // ===== list_models ================================================

    private Mono<CallToolResult> listModels(McpCaller caller, ToolArgs args) {
        String modality = args.text("modality") == null ? null : args.text("modality").trim().toUpperCase(Locale.ROOT);
        if (modality != null && !List.of("IMAGE", "VIDEO", "TEXT").contains(modality)) {
            throw ToolArgs.badRequest("modality must be image, video or text");
        }
        return models.list().map(list -> {
            List<ModelsController.ModelEntry> found = list.data().stream()
                    .filter(entry -> modality == null || modality.equals(entry.godance().get("modality")))
                    .toList();
            return support.results().success(found.size() + " models. Prices are in microcents before the "
                    + "platform fee (markup_percent); estimate_cost gives the exact price of a request.",
                    Map.of("models", found));
        });
    }

    // ===== estimate_cost ==============================================

    private Mono<CallToolResult> estimate(McpCaller caller, ToolArgs args) {
        boolean batch = args.has("batch");
        boolean recipe = args.has("recipe_id");
        if (batch && recipe) throw ToolArgs.badRequest("give one of batch, recipe_id, or a model");
        if (batch) {
            BatchRequest request = args.as("batch", BatchRequest.class, json);
            return ToolSupport.blocking(() -> {
                BatchEstimate estimate = batches.estimate(caller.userId(), request);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("kind", "batch");
                out.put("estimated_cost_cents", estimate.totalCents());
                out.put("currency", CURRENCY);
                out.put("rows", estimate.rows());
                out.put("balance_cents", estimate.balanceCents());
                out.put("enough", estimate.enough());
                out.put("limits", estimate.limits());
                return support.results().success("The batch's " + estimate.rows() + " rows cost "
                        + estimate.totalCents() + " cents in all" + (estimate.enough() ? "." : "; the balance ("
                        + estimate.balanceCents() + " cents) does not cover it, so the batch would pause."), out);
            });
        }
        if (recipe) {
            UUID recipeId = args.id("recipe_id", RecipeRefusedException::notFound);
            UUID versionId = args.id("version_id", RecipeRefusedException::notFound);
            return ToolSupport.blocking(() -> {
                RecipePlans.Plan plan = plans.plan(caller.userId(), recipeId, versionId, args);
                Quote quote = plans.quote(plan);
                Map<String, Object> out = priced("recipe_run", quote,
                        plan.inputImages() > 0 ? plan.inputImages() : null);
                out.put("recipe_id", recipeId);
                out.put("version_id", plan.version().id());
                return support.results().success("One run of \"" + plan.recipe().title() + "\" on "
                        + quote.model().modelName() + " costs " + quote.costCents() + " cents.", out);
            });
        }
        String model = args.requiredText("model");
        Integer n = args.integerAtLeast("n", 1);
        Integer seconds = args.integerAtLeast("seconds", 1);
        Integer inputImages = args.integerAtLeast("input_images", 0);
        return ToolSupport.blocking(() -> {
            Quote quote = quotes.any(model, n, seconds, inputImages);
            return support.results().success("This request on " + quote.model().modelName() + " costs "
                    + quote.costCents() + " cents.", priced("request", quote, inputImages));
        });
    }

    private static Map<String, Object> priced(String kind, Quote quote, Integer inputImages) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", kind);
        out.put("model", quote.model().modelName());
        out.put("modality", quote.model().modality().name().toLowerCase(Locale.ROOT));
        out.put("n", quote.n());
        if (quote.seconds() != null) out.put("seconds", quote.seconds());
        if (inputImages != null) out.put("input_images", inputImages);
        out.put("estimated_cost_cents", quote.costCents());
        out.put("currency", CURRENCY);
        return out;
    }

    // ===== Definitions ================================================

    static final String SEARCH_DESCRIPTION = """
            Search Godance recipes: prompts published in the public gallery, each shown with the model, \
            parameters and price of runs made through the API, and the caller's own saved recipes. Every \
            word of the query must appear in a recipe's title, prompt (English or Chinese), tags or model. \
            Free. Each result says where it is from: a gallery result has a slug, one of the caller's own \
            has an id. Next: get_recipe for its prompt or template, variables, input slots and price.""";

    static final String SEARCH_INPUT = """
            {"type": "object",
             "properties": {
               "query": {"type": "string", "maxLength": 200, "description": "Words to look for; all must match. Leave out to list recipes."},
               "modality": {"type": "string", "enum": ["image", "video"]},
               "source": {"type": "string", "enum": ["all", "gallery", "mine"], "description": "Where to search; all by default."},
               "limit": {"type": "integer", "minimum": 1, "maximum": 50, "description": "At most this many results; 10 by default."}
             },
             "additionalProperties": false}""";

    static final String SEARCH_OUTPUT = """
            {"type": "object",
             "properties": {
               "results": {"type": "array", "items": {"type": "object",
                 "properties": {"source": {"type": "string", "enum": ["gallery", "mine"]}},
                 "required": ["source"]}},
               "gallery_available": {"type": "boolean", "description": "Whether the public gallery could be searched; absent when source is mine."}
             },
             "required": ["results"]}""";

    static final String GET_DESCRIPTION = """
            One recipe with what is needed to run it. With slug: a published gallery recipe, its prompt in \
            English and Chinese (or its template with variables), input slots with samples, and each run's \
            model, parameters and cost. With id: one of the caller's own recipes, its latest version's \
            template, variables, input slots, model and parameters, what one run of it costs now, and its \
            recent runs. Free.""";

    static final String GET_INPUT = """
            {"type": "object",
             "properties": {
               "slug": {"type": "string", "description": "A gallery recipe's slug, from search_recipes."},
               "id": {"type": "string", "description": "One of the caller's recipe ids, from search_recipes."}
             },
             "additionalProperties": false}""";

    static final String GET_OUTPUT = """
            {"type": "object",
             "properties": {"source": {"type": "string", "enum": ["gallery", "mine"]}},
             "required": ["source"]}""";

    static final String MODELS_DESCRIPTION = """
            The models Godance serves: id, modality, capabilities (sizes, durations, how many outputs a \
            request may ask for) and prices (per image, per input image of an edit, per second of video), \
            before the platform fee in markup_percent. Free. For the exact price of a request, use \
            estimate_cost.""";

    static final String MODELS_INPUT = """
            {"type": "object",
             "properties": {"modality": {"type": "string", "enum": ["image", "video", "text"]}},
             "additionalProperties": false}""";

    static final String MODELS_OUTPUT = """
            {"type": "object",
             "properties": {"models": {"type": "array", "items": {"type": "object",
               "properties": {"id": {"type": "string"}}, "required": ["id"]}}},
             "required": ["models"]}""";

    static final String ESTIMATE_DESCRIPTION = """
            The exact price, in US cents, that a request would be charged before it runs (its pre-debit), \
            without charging anything or starting a job. Give one of: model, with n, seconds (video) and \
            input_images (images given to an edit), for a generate_image or generate_video request; \
            recipe_id (with version_id, model, n, seconds or size to change them) for a run_recipe; or batch \
            (version_id, rows, and model or params to change them) for a start_batch. Call it before any \
            tool that spends, and pass the price as that tool's max_cost_cents.""";

    static final String ESTIMATE_INPUT = """
            {"type": "object",
             "properties": {
               "model": {"type": "string", "description": "A model id from list_models."},
               "n": {"type": "integer", "minimum": 1, "description": "How many images or videos; 1 by default."},
               "seconds": {"type": "integer", "minimum": 1, "description": "A video's length; the model's default when left out."},
               "size": {"type": "string"},
               "input_images": {"type": "integer", "minimum": 0, "description": "How many images an edit is given."},
               "recipe_id": {"type": "string", "description": "One of the caller's recipe ids: the price of one run_recipe."},
               "version_id": {"type": "string", "description": "With recipe_id: a version of it; the latest when left out."},
               "batch": {"type": "object", "description": "A start_batch request without max_cost_cents: version_id, rows, and optionally model, params and max_concurrency.",
                 "properties": {
                   "version_id": {"type": "string"},
                   "model": {"type": "string"},
                   "params": {"type": "object"},
                   "rows": {"type": "array", "items": {"type": "object"}},
                   "max_concurrency": {"type": "integer", "minimum": 1}
                 },
                 "required": ["version_id", "rows"]}
             },
             "additionalProperties": false}""";

    static final String ESTIMATE_OUTPUT = """
            {"type": "object",
             "properties": {
               "kind": {"type": "string", "enum": ["request", "recipe_run", "batch"]},
               "estimated_cost_cents": {"type": "integer"},
               "currency": {"type": "string"}
             },
             "required": ["kind", "estimated_cost_cents", "currency"]}""";
}
