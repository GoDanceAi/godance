package ai.godance.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.domain.Modality;
import ai.godance.domain.PublishedRecipe;
import ai.godance.domain.Recipe;
import ai.godance.domain.RecipeVersion;
import ai.godance.repository.RecipeRepository;
import ai.godance.repository.RecipeVersionRepository;
import ai.godance.service.RecipeTemplate.Variable;
import ai.godance.web.dto.InputDtos.InputRef;
import ai.godance.web.dto.RecipeDtos.RecipeRef;

/**
 * Turns what a generation request asks for into the prompt that is run
 * (round 10 brief 03). A request carries a {@code prompt}, or a
 * {@code recipe}: a version of one of the caller's recipes, and values for
 * its variables.
 *
 * <p><b>The rendered prompt is the prompt.</b> A recipe is rendered here,
 * once, before anything is priced or charged. From then on the request is
 * an ordinary one with that text as its prompt: pricing, the provider, the
 * stored request and the job's view all see the same words.
 *
 * <p>A request with no recipe passes through untouched and is never read as
 * a template: braces in a plain prompt are the author's own.
 */
@Service
public class RecipeRunService {

    private final RecipeRepository recipes;
    private final RecipeVersionRepository versions;
    private final PublishService published;

    public RecipeRunService(RecipeRepository recipes, RecipeVersionRepository versions, PublishService published) {
        this.recipes = recipes;
        this.versions = versions;
        this.published = published;
    }

    /**
     * The prompt to run. {@code recipeVersionId} and {@code variables} are
     * null for a plain prompt; for a recipe, {@code variables} is the value
     * every variable ended up with, given or default.
     */
    public record RunPrompt(String prompt, UUID recipeVersionId, Map<String, String> variables,
                            List<SlotInput> inputs, UUID publishedRecipeId) {

        /** A prompt with no recipe inputs. */
        public RunPrompt(String prompt, UUID recipeVersionId, Map<String, String> variables) {
            this(prompt, recipeVersionId, variables, List.of(), null);
        }

        /** A run of the caller's own recipe, or a plain prompt. */
        public RunPrompt(String prompt, UUID recipeVersionId, Map<String, String> variables, List<SlotInput> inputs) {
            this(prompt, recipeVersionId, variables, inputs, null);
        }

        /** Whether the recipe names its own inputs: it then runs with them and no others. */
        public boolean hasInputs() {
            return !inputs.isEmpty();
        }

        /** The inputs with this role, in the order the recipe's slots have them. */
        public List<InputRef> inputsWithRole(String role) {
            return inputs.stream().filter(input -> input.role().equals(role)).map(SlotInput::ref).toList();
        }

        /**
         * The origin of a job that runs this: what it already was, and the
         * recipe version, or the published recipe, when there is one.
         */
        public JobOrigin origin(JobOrigin base) {
            if (publishedRecipeId != null) return base.withPublishedRecipe(publishedRecipeId, variables);
            return recipeVersionId == null ? base : base.withRecipe(recipeVersionId, variables);
        }
    }

    /** What a slot of the recipe was given for this run: the slot's role, and the input named for it. */
    public record SlotInput(String slot, String role, InputRef ref) { }

    /**
     * @param modality the kind of job the route makes
     * @throws RecipeRefusedException 400 {@code prompt_and_recipe}; 404
     *         {@code recipe_not_found} (another user's answers the same);
     *         400 {@code recipe_modality_mismatch},
     *         {@code recipe_variable_unknown} or {@code recipe_variable_too_long}
     * @throws ResponseStatusException 400 when there is neither a prompt nor a recipe
     */
    @Transactional(readOnly = true)
    public RunPrompt resolve(UUID userId, Modality modality, String prompt, RecipeRef recipe) {
        if (recipe == null) {
            if (prompt == null || prompt.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "prompt must not be blank");
            }
            return new RunPrompt(prompt, null, null);
        }
        if (prompt != null) throw RecipeRefusedException.promptAndRecipe();
        if (recipe.publishedId() != null) {
            if (recipe.versionId() != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "send recipe.version_id or recipe.published_id, not both");
            }
            // A published recipe: anyone may run one that is public, with the version its author published.
            PublishedRecipe pub = published.runnable(recipe.publishedId());
            requireModality(pub.getModality(), modality);
            Rendered done = render(pub.getTemplate(), pub.getVariables(), recipe.variables());
            return new RunPrompt(done.prompt(), null, done.used(), slotInputs(pub.getInputs(), recipe.inputs()),
                    pub.getId());
        }
        if (recipe.versionId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "recipe.version_id is required");
        }

        RecipeVersion version = versions.findById(recipe.versionId()).orElseThrow(RecipeRefusedException::notFound);
        Recipe owner = recipes.findById(version.getRecipeId())
                .filter(r -> r.getUserId().equals(userId))
                .orElseThrow(RecipeRefusedException::notFound);
        requireModality(owner.getModality(), modality);
        Rendered done = render(version.getTemplate(), version.getVariables(), recipe.variables());
        return new RunPrompt(done.prompt(), version.getId(), done.used(), slotInputs(version.getInputs(), recipe.inputs()));
    }

    private static void requireModality(Modality makes, Modality route) {
        if (makes != route) {
            throw RecipeRefusedException.modalityMismatch("this recipe makes "
                    + (makes == Modality.VIDEO ? "video" : "images") + "; send it to /v1/"
                    + (makes == Modality.VIDEO ? "videos" : "images") + "/generations");
        }
    }

    /** A rendered template, and the value every variable ended up with, given or default. */
    private record Rendered(String prompt, Map<String, String> used) { }

    private static Rendered render(String template, List<Map<String, String>> variables, Map<String, String> values) {
        List<Variable> declared = variables.stream()
                .map(v -> new Variable(v.get("name"), v.get("default")))
                .toList();
        Map<String, String> given = values == null ? Map.of() : values;
        for (Map.Entry<String, String> value : given.entrySet()) {
            if (declared.stream().noneMatch(v -> v.name().equals(value.getKey()))) {
                throw RecipeRefusedException.variableUnknown(value.getKey());
            }
            if (value.getValue() != null && value.getValue().length() > RecipeService.VALUE_MAX) {
                throw RecipeRefusedException.variableTooLong(value.getKey(), RecipeService.VALUE_MAX);
            }
        }

        String rendered = RecipeTemplate.render(template, declared, given);
        if (rendered.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "the recipe renders to an empty prompt");
        }
        // What each variable ended up as, in the order the version declares them.
        Map<String, String> used = new LinkedHashMap<>();
        for (Variable v : declared) used.put(v.name(), given.get(v.name()) != null ? given.get(v.name()) : v.defaultValue());
        return new Rendered(rendered, used);
    }

    /**
     * The input the request names for each slot of the version, in the
     * slots' order. Every slot needs one; a name the version has no slot
     * for is refused. Whether each input may be used, and is the caller's,
     * is checked later, by the route that places it.
     */
    private static List<SlotInput> slotInputs(List<Map<String, String>> slots, Map<String, InputRef> given) {
        Map<String, InputRef> named = given == null ? Map.of() : given;
        for (String name : named.keySet()) {
            if (slots.stream().noneMatch(slot -> slot.get("slot").equals(name))) {
                throw RecipeRefusedException.inputUnknown(name);
            }
        }
        List<SlotInput> inputs = new ArrayList<>(slots.size());
        for (Map<String, String> slot : slots) {
            InputRef ref = named.get(slot.get("slot"));
            if (ref == null) throw RecipeRefusedException.inputMissing(slot.get("slot"));
            inputs.add(new SlotInput(slot.get("slot"), slot.get("role"), ref));
        }
        return List.copyOf(inputs);
    }
}
