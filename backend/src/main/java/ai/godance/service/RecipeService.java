package ai.godance.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import ai.godance.config.RecipesProperties;
import ai.godance.domain.AuditOutcome;
import ai.godance.domain.Modality;
import ai.godance.domain.Recipe;
import ai.godance.domain.RecipeVersion;
import ai.godance.repository.RecipeRepository;
import ai.godance.repository.RecipeRepository.Attempts;
import ai.godance.repository.RecipeVersionRepository;
import ai.godance.web.dto.RecipeDtos.CreateRecipeRequest;
import ai.godance.web.dto.RecipeDtos.NewVersionRequest;
import ai.godance.web.dto.RecipeDtos.RecipeVariable;
import ai.godance.web.dto.RecipeDtos.RecipeVersionView;
import ai.godance.web.dto.RecipeDtos.RecipeView;

/**
 * A user's own recipes (round 10 brief 03): a prompt template with
 * variables, a model and its parameters, kept with every version it has
 * had. {@code /v1/recipes/**}.
 *
 * <p><b>Private to their owner.</b> Every read and write is by the owner's
 * id as well as the recipe's, and another user's recipe answers exactly as
 * one that does not exist: 404 {@code recipe_not_found}.
 *
 * <p>A version never changes once written. Saving a change writes the next
 * number; saving what the latest version already holds writes nothing.
 * Nothing here costs credit or touches the ledger.
 */
@Service
@EnableConfigurationProperties(RecipesProperties.class)
public class RecipeService {

    /** Largest page {@code limit} accepts. */
    public static final int MAX_LIMIT = 100;

    static final int TITLE_MAX = 200;
    static final int TEMPLATE_MAX = 32_000;
    static final int VARIABLES_MAX = 20;
    /** The most characters a variable's value, its default included, may have. */
    static final int VALUE_MAX = 2_000;
    static final int MODEL_MAX = 128;
    static final int PARAMS_MAX = 2_000;

    /** First key of the per-user advisory lock; the second is the user id's hash. */
    static final int RECIPES_LOCK_SPACE = 7_000_029;

    /** A cursor after every recipe: the first page. */
    private static final Instant END_OF_TIME = Instant.parse("9999-12-31T23:59:59Z");
    private static final UUID HIGHEST_UUID = new UUID(-1L, -1L);

    /** Writes params the same way whatever order their keys came in, to tell two versions apart. */
    private static final ObjectMapper CANONICAL = JsonMapper.builderWithJackson2Defaults()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private final RecipeRepository recipes;
    private final RecipeVersionRepository versions;
    private final AuditLogService audit;
    private final RecipesProperties props;

    public RecipeService(RecipeRepository recipes, RecipeVersionRepository versions, AuditLogService audit,
                         RecipesProperties props) {
        this.recipes = recipes;
        this.versions = versions;
        this.audit = audit;
        this.props = props;
    }

    /** What a version holds, checked and ready to store. */
    private record Content(String template, List<Map<String, String>> variables, String model,
                           Map<String, Object> params) { }

    /** A new version, or the latest one when nothing had changed. */
    public record SavedVersion(RecipeVersionView version, boolean created) { }

    // ===== Create =======================================================

    /**
     * Creates a recipe with its first version.
     *
     * @throws RecipeRefusedException 400 {@code invalid_recipe}; 409
     *         {@code recipe_limit_reached} at {@code godance.recipes.max-per-account}
     * @throws ResponseStatusException 400 {@code invalid_gallery_recipe} for a malformed slug
     */
    @Transactional
    public RecipeView create(UUID userId, CreateRecipeRequest req, String ip) {
        if (req == null) throw RecipeRefusedException.invalid("a recipe needs a title, a modality, a template and a model");
        String title = title(req.title());
        Modality modality = modality(req.modality());
        Content content = content(req.template(), req.variables(), req.model(), req.params());
        String source = GalleryRecipeSlug.requireValidOrNull(req.sourceGalleryRecipe());

        // Counted and written one request at a time, so two cannot both take the last place.
        recipes.lockRecipesOf(RECIPES_LOCK_SPACE, userId.hashCode());
        if (recipes.countByUserId(userId) >= props.maxPerAccount()) {
            throw RecipeRefusedException.limitReached("You have " + props.maxPerAccount()
                    + " recipes, the most an account can keep. Delete one to add another.");
        }

        Recipe recipe = new Recipe();
        recipe.setUserId(userId);
        recipe.setTitle(title);
        recipe.setModality(modality);
        recipe.setSourceGalleryRecipe(source);
        recipes.save(recipe);
        RecipeVersion first = versions.save(version(recipe.getId(), 1, content));

        audit.record(AuditLogService.EVT_RECIPE_CREATED, AuditOutcome.SUCCESS, userId, ip,
                Map.of("recipeId", recipe.getId().toString(), "modality", modality.name()));
        return RecipeView.from(recipe, first, 0, 0);
    }

    // ===== Read =========================================================

    /**
     * The caller's recipes, newest first. {@code limit} null returns them
     * all. {@code before} is one of the caller's recipe ids (400 otherwise)
     * and returns only recipes older than it.
     */
    @Transactional(readOnly = true)
    public List<RecipeView> list(UUID userId, Integer limit, UUID before) {
        if (limit != null && (limit < 1 || limit > MAX_LIMIT)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and " + MAX_LIMIT);
        }
        Instant beforeCreatedAt = END_OF_TIME;
        UUID beforeId = HIGHEST_UUID;
        if (before != null) {
            Recipe cursor = recipes.findById(before)
                    .filter(r -> r.getUserId().equals(userId))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "before must be the id of one of your recipes"));
            beforeCreatedAt = cursor.getCreatedAt();
            beforeId = cursor.getId();
        }
        List<Recipe> page = recipes.findPageForUser(userId, beforeCreatedAt, beforeId,
                limit == null ? Integer.MAX_VALUE : limit);
        if (page.isEmpty()) return List.of();

        List<UUID> ids = page.stream().map(Recipe::getId).toList();
        Map<UUID, RecipeVersion> latest = new HashMap<>();
        for (RecipeVersion v : versions.findLatestOf(ids)) latest.put(v.getRecipeId(), v);
        Map<UUID, Attempts> attempts = new HashMap<>();
        for (Attempts a : recipes.attemptsOf(ids)) attempts.put(a.getRecipeId(), a);
        return page.stream().map(r -> view(r, latest.get(r.getId()), attempts.get(r.getId()))).toList();
    }

    /** One of the caller's recipes; 404 {@code recipe_not_found} otherwise. */
    @Transactional(readOnly = true)
    public RecipeView get(UUID userId, UUID recipeId) {
        return view(requireOwned(userId, recipeId));
    }

    /** A recipe's versions, newest first; 404 {@code recipe_not_found} for a recipe that is not the caller's. */
    @Transactional(readOnly = true)
    public List<RecipeVersionView> versions(UUID userId, UUID recipeId) {
        requireOwned(userId, recipeId);
        return versions.findByRecipeIdOrderByNumberDesc(recipeId).stream().map(RecipeVersionView::from).toList();
    }

    // ===== Change =======================================================

    /** Renames the caller's recipe. */
    @Transactional
    public RecipeView rename(UUID userId, UUID recipeId, String newTitle) {
        String title = title(newTitle);
        Recipe recipe = requireOwned(userId, recipeId);
        if (!title.equals(recipe.getTitle())) {
            recipe.setTitle(title);
            recipe.setUpdatedAt(Instant.now());
            recipes.save(recipe);
        }
        return view(recipe);
    }

    /**
     * Writes the next version, when the template, variables, model or params
     * differ from the latest. When none does, writes nothing and returns
     * the latest.
     *
     * @throws RecipeRefusedException 404 {@code recipe_not_found}; 400
     *         {@code invalid_recipe}; 409 {@code recipe_limit_reached} at
     *         {@code godance.recipes.max-versions}
     */
    @Transactional
    public SavedVersion newVersion(UUID userId, UUID recipeId, NewVersionRequest req) {
        if (req == null) throw RecipeRefusedException.invalid("a version needs a template and a model");
        Content content = content(req.template(), req.variables(), req.model(), req.params());
        // The row lock numbers two saves of one recipe one after the other.
        recipes.lockOwned(recipeId, userId).orElseThrow(RecipeRefusedException::notFound);
        RecipeVersion latest = versions.findFirstByRecipeIdOrderByNumberDesc(recipeId).orElseThrow();
        if (same(latest, content)) return new SavedVersion(RecipeVersionView.from(latest), false);

        if (versions.countByRecipeId(recipeId) >= props.maxVersions()) {
            throw RecipeRefusedException.limitReached("This recipe has " + props.maxVersions()
                    + " versions, the most a recipe can keep. Save the setup as a new recipe instead.");
        }
        RecipeVersion next = versions.save(version(recipeId, latest.getNumber() + 1, content));
        Recipe recipe = recipes.findById(recipeId).orElseThrow();
        recipe.setUpdatedAt(Instant.now());
        recipes.save(recipe);
        return new SavedVersion(RecipeVersionView.from(next), true);
    }

    /** Deletes the caller's recipe and its versions. Its jobs stay, no longer linked to a recipe. */
    @Transactional
    public void delete(UUID userId, UUID recipeId, String ip) {
        Recipe recipe = requireOwned(userId, recipeId);
        String title = recipe.getTitle();
        if (recipes.deleteOwned(recipeId, userId) == 0) throw RecipeRefusedException.notFound();
        audit.record(AuditLogService.EVT_RECIPE_DELETED, AuditOutcome.SUCCESS, userId, ip,
                Map.of("recipeId", recipeId.toString(), "title", title));
    }

    // ===== Helpers ======================================================

    private Recipe requireOwned(UUID userId, UUID recipeId) {
        return recipes.findById(recipeId)
                // Another user's recipe answers as one that does not exist.
                .filter(r -> r.getUserId().equals(userId))
                .orElseThrow(RecipeRefusedException::notFound);
    }

    private RecipeView view(Recipe recipe) {
        RecipeVersion latest = versions.findFirstByRecipeIdOrderByNumberDesc(recipe.getId()).orElseThrow();
        List<Attempts> attempts = recipes.attemptsOf(List.of(recipe.getId()));
        return view(recipe, latest, attempts.isEmpty() ? null : attempts.get(0));
    }

    private static RecipeView view(Recipe recipe, RecipeVersion latest, Attempts attempts) {
        return RecipeView.from(recipe, latest, attempts == null ? 0 : attempts.getAttempts(),
                attempts == null ? 0 : attempts.getSpentCents());
    }

    private static RecipeVersion version(UUID recipeId, int number, Content content) {
        RecipeVersion v = new RecipeVersion();
        v.setRecipeId(recipeId);
        v.setNumber(number);
        v.setTemplate(content.template());
        v.setVariables(content.variables());
        v.setModel(content.model());
        v.setParams(content.params());
        return v;
    }

    /** Whether a version already holds exactly this. */
    private static boolean same(RecipeVersion latest, Content content) {
        return latest.getTemplate().equals(content.template())
                && latest.getModel().equals(content.model())
                && latest.getVariables().equals(content.variables())
                && CANONICAL.writeValueAsString(latest.getParams()).equals(CANONICAL.writeValueAsString(content.params()));
    }

    private static String title(String raw) {
        String title = raw == null ? "" : raw.strip();
        if (title.isEmpty()) throw RecipeRefusedException.invalid("a recipe needs a title");
        if (title.length() > TITLE_MAX) throw RecipeRefusedException.invalid("the title is at most " + TITLE_MAX + " characters");
        return title;
    }

    private static Modality modality(String raw) {
        return switch (raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT)) {
            case "IMAGE" -> Modality.IMAGE;
            case "VIDEO" -> Modality.VIDEO;
            default -> throw RecipeRefusedException.invalid("modality must be IMAGE or VIDEO");
        };
    }

    /**
     * Checks a version's content against the template rules
     * ({@link RecipeTemplate}) and the limits: a template of at most 32,000
     * characters, at most 20 variables, each default at most 2,000.
     */
    private static Content content(String template, List<RecipeVariable> declared, String rawModel,
                                   Map<String, Object> rawParams) {
        if (template == null || template.isBlank()) throw RecipeRefusedException.invalid("a recipe needs a template");
        if (template.length() > TEMPLATE_MAX) {
            throw RecipeRefusedException.invalid("the template is at most " + TEMPLATE_MAX + " characters");
        }
        List<RecipeVariable> variables = declared == null ? List.of() : declared;
        if (variables.size() > VARIABLES_MAX) {
            throw RecipeRefusedException.invalid("a template has at most " + VARIABLES_MAX + " variables");
        }
        List<Map<String, String>> stored = new ArrayList<>(variables.size());
        Set<String> names = new HashSet<>();
        for (RecipeVariable variable : variables) {
            if (variable == null || !RecipeTemplate.isVariableName(variable.name())) {
                throw RecipeRefusedException.invalid("a variable's name is 1–" + RecipeTemplate.NAME_MAX_LENGTH
                        + " ASCII letters, digits, spaces, hyphens or underscores, starting and ending with a letter or digit");
            }
            if (!names.add(variable.name())) {
                throw RecipeRefusedException.invalid("the variable \"" + variable.name() + "\" is declared more than once");
            }
            if (variable.defaultValue() == null) {
                throw RecipeRefusedException.invalid("the variable \"" + variable.name() + "\" needs a default, which may be empty");
            }
            if (variable.defaultValue().length() > VALUE_MAX) {
                throw RecipeRefusedException.invalid("the default of \"" + variable.name() + "\" is at most " + VALUE_MAX + " characters");
            }
            Map<String, String> row = new LinkedHashMap<>();
            row.put("name", variable.name());
            row.put("default", variable.defaultValue());
            stored.add(row);
        }
        List<RecipeTemplate.Problem> problems = RecipeTemplate.check(template, List.copyOf(names.isEmpty()
                ? List.<String>of() : variables.stream().map(RecipeVariable::name).toList()));
        if (!problems.isEmpty()) throw RecipeRefusedException.invalid(problems.get(0).message());

        String model = rawModel == null ? "" : rawModel.strip();
        if (model.isEmpty()) throw RecipeRefusedException.invalid("a recipe needs a model");
        if (model.length() > MODEL_MAX) throw RecipeRefusedException.invalid("the model id is at most " + MODEL_MAX + " characters");

        Map<String, Object> params = rawParams == null ? new LinkedHashMap<>() : new LinkedHashMap<>(rawParams);
        if (CANONICAL.writeValueAsString(params).length() > PARAMS_MAX) {
            throw RecipeRefusedException.invalid("params is at most " + PARAMS_MAX + " characters of JSON");
        }
        return new Content(template, stored, model, params);
    }
}
