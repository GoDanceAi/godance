package ai.godance.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.ObjectMapper;

import ai.godance.config.AttributionProperties;
import ai.godance.repository.UserRepository;
import ai.godance.web.dto.AttributionDtos.FirstTouchRequest;

/**
 * A sign-up's first touch: what brought the account to the app, as the app
 * saw it before anyone signed in (round 10 brief 01). Campaign tags, the
 * gallery recipe a "Run this" link named, the landing page and the
 * referring site. First-party data in our own database; the admin
 * console's funnel report counts sign-ups by it and never shows whose it is.
 *
 * <p>It is written once: the first accepted call is kept. A later call, or
 * one for an account older than
 * {@code godance.attribution.first-touch-window-hours}, changes nothing and
 * is not an error, so the app can hand it in without knowing either.
 */
@Service
@EnableConfigurationProperties(AttributionProperties.class)
public class AttributionService {

    /** The {@code code} of the 400 a malformed first touch answers. */
    public static final String CODE_INVALID = "invalid_first_touch";

    /** The most characters a field may have. */
    static final int FIELD_MAX = 100;

    private final UserRepository users;
    private final AttributionProperties props;
    private final ObjectMapper json;

    public AttributionService(UserRepository users, AttributionProperties props, ObjectMapper json) {
        this.users = users;
        this.props = props;
        this.json = json;
    }

    /**
     * Keeps {@code req} as the account's first touch when it has none yet
     * and is still inside the window. True when it was written.
     *
     * @throws ResponseStatusException 400 {@code invalid_first_touch} when no
     *         field is present, or one is too long or not printable; 400
     *         {@code invalid_gallery_recipe} when the recipe is not a slug
     */
    @Transactional
    public boolean recordFirstTouch(UUID userId, FirstTouchRequest req) {
        Map<String, String> touch = validated(req);
        Instant now = Instant.now();
        // One conditional update: of two calls at once, only the first writes.
        return users.recordFirstTouch(userId, json.writeValueAsString(touch), now,
                now.minus(props.firstTouchWindow())) == 1;
    }

    /** The fields that are present, by their wire names, trimmed. */
    static Map<String, String> validated(FirstTouchRequest req) {
        if (req == null) throw invalid("a first touch has at least one field");
        Map<String, String> touch = new LinkedHashMap<>();
        put(touch, "utm_source", req.utmSource());
        put(touch, "utm_medium", req.utmMedium());
        put(touch, "utm_campaign", req.utmCampaign());
        String recipe = present(req.galleryRecipe());
        if (recipe != null) touch.put("gallery_recipe", GalleryRecipeSlug.requireValidOrNull(recipe));
        put(touch, "landing_path", req.landingPath());
        put(touch, "referrer_host", req.referrerHost());
        if (touch.isEmpty()) throw invalid("a first touch has at least one field");
        return touch;
    }

    private static void put(Map<String, String> touch, String name, String raw) {
        String value = present(raw);
        if (value == null) return;
        if (value.codePointCount(0, value.length()) > FIELD_MAX) {
            throw invalid(name + " is at most " + FIELD_MAX + " characters");
        }
        if (!value.codePoints().allMatch(AttributionService::printable)) {
            throw invalid(name + " may hold printable characters only");
        }
        touch.put(name, value);
    }

    /** Null for a field that is absent or blank; otherwise the value without surrounding spaces. */
    private static String present(String raw) {
        if (raw == null) return null;
        String value = raw.strip();
        return value.isEmpty() ? null : value;
    }

    /**
     * Letters, marks, digits, punctuation, symbols and the plain space.
     * Not control or format characters (which include the bidirectional
     * overrides), line or paragraph separators, unpaired surrogates,
     * private-use or unassigned code points.
     */
    private static boolean printable(int codePoint) {
        return switch (Character.getType(codePoint)) {
            case Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR,
                 Character.PARAGRAPH_SEPARATOR, Character.SURROGATE, Character.PRIVATE_USE,
                 Character.UNASSIGNED -> false;
            default -> true;
        };
    }

    private static ResponseStatusException invalid(String detail) {
        ResponseStatusException e = new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
        e.getBody().setProperty("code", CODE_INVALID);
        return e;
    }
}
