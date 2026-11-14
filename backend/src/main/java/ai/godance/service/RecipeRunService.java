package ai.godance.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.domain.Modality;
import ai.godance.domain.Recipe;
import ai.godance.domain.RecipeVersion;
import ai.godance.repository.RecipeRepository;
import ai.godance.repository.RecipeVersionRepository;
import ai.godance.service.RecipeTemplate.Variable;
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

    public RecipeRunService(RecipeRepository recipes, RecipeVersionRepository versions) {
        this.recipes = recipes;
        this.versions = versions;
    }

    /**
     * The prompt to run. {@code recipeVersionId} and {@code variables} are
     * null for a plain prompt; for a recipe, {@code variables} is the value
     * every variable ended up with, given or default.
     */
    public record RunPrompt(String prompt, UUID recipeVersionId, Map<String, String> variables) {

        /** The origin of a job that runs this: what it already was, and the recipe version when there is one. */
        public JobOrigin origin(JobOrigin base) {
            return recipeVersionId == null ? base : base.withRecipe(recipeVersionId, variables);
        }
    }

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
        if (recipe.versionId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "recipe.version_id is required");
        }

        RecipeVersion version = versions.findById(recipe.versionId()).orElseThrow(RecipeRefusedException::notFound);
        Recipe owner = recipes.findById(version.getRecipeId())
                .filter(r -> r.getUserId().equals(userId))
                .orElseThrow(RecipeRefusedException::notFound);
        if (owner.getModality() != modality) {
            throw RecipeRefusedException.modalityMismatch("this recipe makes "
                    + (owner.getModality() == Modality.VIDEO ? "video" : "images") + "; send it to /v1/"
                    + (owner.getModality() == Modality.VIDEO ? "videos" : "images") + "/generations");
        }

        List<Variable> declared = version.getVariables().stream()
                .map(v -> new Variable(v.get("name"), v.get("default")))
                .toList();
        Map<String, String> given = recipe.variables() == null ? Map.of() : recipe.variables();
        for (Map.Entry<String, String> value : given.entrySet()) {
            if (declared.stream().noneMatch(v -> v.name().equals(value.getKey()))) {
                throw RecipeRefusedException.variableUnknown(value.getKey());
            }
            if (value.getValue() != null && value.getValue().length() > RecipeService.VALUE_MAX) {
                throw RecipeRefusedException.variableTooLong(value.getKey(), RecipeService.VALUE_MAX);
            }
        }

        String rendered = RecipeTemplate.render(version.getTemplate(), declared, given);
        if (rendered.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "the recipe renders to an empty prompt");
        }
        // What each variable ended up as, in the order the version declares them.
        Map<String, String> used = new LinkedHashMap<>();
        for (Variable v : declared) used.put(v.name(), given.get(v.name()) != null ? given.get(v.name()) : v.defaultValue());
        return new RunPrompt(rendered, version.getId(), used);
    }
}
