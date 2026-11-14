package ai.godance.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * A refused recipe request: problem+json whose {@code code} extension member
 * says which kind, so a client can branch without reading {@code detail}.
 */
public final class RecipeRefusedException extends ResponseStatusException {

    /** No such recipe or version for this caller. Another user's answers the same. 404. */
    public static final String CODE_NOT_FOUND = "recipe_not_found";
    /** An image recipe on the video route, or the other way round. 400. */
    public static final String CODE_MODALITY_MISMATCH = "recipe_modality_mismatch";
    /** A value for a variable the version does not declare. 400. */
    public static final String CODE_VARIABLE_UNKNOWN = "recipe_variable_unknown";
    /** A value longer than a variable may hold. 400. */
    public static final String CODE_VARIABLE_TOO_LONG = "recipe_variable_too_long";
    /** A request with both {@code prompt} and {@code recipe}. 400. */
    public static final String CODE_PROMPT_AND_RECIPE = "prompt_and_recipe";
    /** The account's recipes, or the recipe's versions, are at their limit. 409. */
    public static final String CODE_LIMIT_REACHED = "recipe_limit_reached";
    /** A recipe or version whose title, template, variables, model or params are not acceptable. 400. */
    public static final String CODE_INVALID = "invalid_recipe";

    private RecipeRefusedException(HttpStatus status, String detail, String code) {
        super(status, detail);
        getBody().setProperty("code", code);
    }

    public static RecipeRefusedException notFound() {
        return new RecipeRefusedException(HttpStatus.NOT_FOUND, "recipe not found", CODE_NOT_FOUND);
    }

    static RecipeRefusedException modalityMismatch(String detail) {
        return new RecipeRefusedException(HttpStatus.BAD_REQUEST, detail, CODE_MODALITY_MISMATCH);
    }

    static RecipeRefusedException variableUnknown(String name) {
        return new RecipeRefusedException(HttpStatus.BAD_REQUEST,
                "the recipe version has no variable \"" + name + "\"", CODE_VARIABLE_UNKNOWN);
    }

    static RecipeRefusedException variableTooLong(String name, int max) {
        return new RecipeRefusedException(HttpStatus.BAD_REQUEST,
                "the value of \"" + name + "\" is longer than " + max + " characters", CODE_VARIABLE_TOO_LONG);
    }

    static RecipeRefusedException promptAndRecipe() {
        return new RecipeRefusedException(HttpStatus.BAD_REQUEST,
                "send either prompt or recipe, not both", CODE_PROMPT_AND_RECIPE);
    }

    static RecipeRefusedException limitReached(String detail) {
        return new RecipeRefusedException(HttpStatus.CONFLICT, detail, CODE_LIMIT_REACHED);
    }

    static RecipeRefusedException invalid(String detail) {
        return new RecipeRefusedException(HttpStatus.BAD_REQUEST, detail, CODE_INVALID);
    }
}
