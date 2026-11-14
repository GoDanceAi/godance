package ai.godance.service;

import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * OpenAI's {@code response_format} for images: how a response that carries
 * the images carries them.
 */
public enum ImageResponseFormat {
    /** A link to each image. */
    URL,
    /** Each image itself, base64-encoded. */
    B64_JSON;

    /**
     * The format a request names, or null when it names none.
     *
     * @throws ResponseStatusException 400 for any other value
     */
    public static ImageResponseFormat parse(String value) {
        if (value == null) return null;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "url" -> URL;
            case "b64_json" -> B64_JSON;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "response_format must be \"url\" or \"b64_json\"");
        };
    }

    /**
     * Whether OpenAI's own API returns this model's images as
     * {@code b64_json} when a request names no format. It does for the GPT
     * Image models, and Godance does the same for that family's ids.
     *
     * <p>OpenAI's Images API reference, "Create image", says of the
     * response's {@code b64_json}: "Returned by default for GPT image
     * models, or when response_format is set to b64_json"; and of
     * {@code response_format}: "Legacy response format parameter for
     * retired image models. Unsupported for GPT image models, which always
     * return base64-encoded images."
     * (https://developers.openai.com/api/reference/resources/images/methods/generate,
     * read 2026-11-14.)
     */
    public static boolean inlineByDefault(String model) {
        if (model == null) return false;
        String id = model.toLowerCase(Locale.ROOT);
        return id.startsWith("gpt-image-") || id.startsWith("chatgpt-image-");
    }
}
