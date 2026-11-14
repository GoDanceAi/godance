package ai.godance.service;

import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The slug of a gallery recipe, as a request names one: lowercase letters,
 * digits and hyphens, at most 80 characters. The gallery is static content
 * on the marketing site and the backend never reads it, so a slug is
 * checked for its shape only.
 */
public final class GalleryRecipeSlug {

    /** The {@code code} of the 400 a malformed slug answers. */
    public static final String CODE_INVALID = "invalid_gallery_recipe";

    private static final Pattern SHAPE = Pattern.compile("[a-z0-9-]{1,80}");

    private GalleryRecipeSlug() { }

    public static boolean isValid(String slug) {
        return slug != null && SHAPE.matcher(slug).matches();
    }

    /**
     * {@code slug} when it is one, or null when none was given.
     *
     * @throws ResponseStatusException 400 {@code invalid_gallery_recipe} for
     *         anything else, the empty string included
     */
    public static String requireValidOrNull(String slug) {
        if (slug == null || isValid(slug)) return slug;
        ResponseStatusException invalid = new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "gallery_recipe must be a recipe's slug: lowercase letters, digits and hyphens, "
                        + "at most 80 characters");
        invalid.getBody().setProperty("code", CODE_INVALID);
        throw invalid;
    }
}
