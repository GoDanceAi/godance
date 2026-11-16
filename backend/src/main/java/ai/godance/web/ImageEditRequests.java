package ai.godance.web;

import java.util.Set;

import org.springframework.http.codec.multipart.Part;
import org.springframework.util.MultiValueMap;

import ai.godance.service.ImageEditRefusedException;
import ai.godance.web.dto.ImageEditDtos.ImageEditRequest;
import ai.godance.web.dto.RecipeDtos.RecipeRef;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A {@code POST /v1/images/edits} request as the route reads it: from JSON,
 * or from the multipart form OpenAI's SDK sends for
 * {@code client.images.edit(…)}. What the route would not use is refused,
 * never dropped, as on {@code POST /v1/videos}; how the request is read
 * strictly, and how the form's bracketed fields are read back into the
 * value they were written from, is {@link StrictRequestReader}'s.
 *
 * <p><b>Nothing is dropped without a word.</b> A field the route does not
 * read answers 400 {@code invalid_parameter}, its {@code param} naming the
 * field, at any depth: OpenAI's own {@code output_format} or
 * {@code background}, which Godance does not act on; {@code style}, which
 * only a generation takes; {@code recipe.variable}. In JSON a field whose
 * value is null carries nothing and passes. A form field sent twice is
 * refused, since only one of the two could be read, except {@code image},
 * which comes once per image.
 *
 * <p><b>The form.</b> The images are file parts named {@code image}, or
 * {@code image[]} as OpenAI's SDK names a list of them, and the mask a part
 * named {@code mask}; the route stores them only after every check that
 * can be made without them. {@code recipe} is the bracketed fields the SDK
 * writes {@code extra_body={"recipe": {…}}} as ({@code recipe[version_id]},
 * {@code recipe[variables][subject]}, {@code recipe[inputs][slot][file_id]}),
 * or JSON text.
 */
final class ImageEditRequests {

    /** The fields both forms share, parsed. The images and the mask are not among them. */
    record Fields(String model, String prompt, Integer n, String size, String quality,
                  String responseFormat, Boolean async, String webhookUrl, String galleryRecipe,
                  RecipeRef recipe) {

        /** The fields of a JSON request. */
        static Fields of(ImageEditRequest request) {
            return new Fields(request.model(), request.prompt(), request.n(), request.size(), request.quality(),
                    request.responseFormat(), request.async(), request.webhookUrl(), request.galleryRecipe(),
                    request.recipe());
        }
    }

    /** An image part. Several images are several parts of this name, or of {@link #IMAGE_LIST}. */
    static final String IMAGE = "image";
    /** An image part, as OpenAI's SDK names the parts of a list of images. */
    static final String IMAGE_LIST = "image[]";
    /** The mask part. */
    static final String MASK = "mask";

    private static final String ROUTE = "POST /v1/images/edits";

    /** The form's fields that are plain text. */
    private static final Set<String> TEXT_FIELDS = Set.of("model", "prompt", "n", "size", "quality",
            "response_format", "async", "webhook_url", "gallery_recipe");

    /**
     * The fields named in a refusal, in JSON and in the form.
     * {@code gallery_recipe} is read but not named: it is for the gallery,
     * which the published docs leave out while it may be switched off.
     */
    private static final String TAKES_JSON = "It takes model, prompt, images, mask, n, size, quality and "
            + "response_format, and the Godance extensions async, recipe and webhook_url.";
    private static final String TAKES_FORM = "It takes model, prompt, image, mask, n, size, quality and "
            + "response_format, and the Godance extensions async, recipe and webhook_url.";

    private final StrictRequestReader jsonReader;
    private final StrictRequestReader formReader;
    private final JavaType request;

    ImageEditRequests(ObjectMapper json) {
        this.jsonReader = new StrictRequestReader(json, new Refusals(false));
        this.formReader = new StrictRequestReader(json, new Refusals(true));
        this.request = jsonReader.type(ImageEditRequest.class);
    }

    // ===== JSON =======================================================

    /** The JSON request body, with every field it has read or refused. */
    ImageEditRequest json(JsonNode body) {
        return jsonReader.body(body, request);
    }

    // ===== The multipart form =========================================

    /**
     * The form's fields, every one of them read or refused. The image and
     * mask parts are left to the caller.
     */
    Fields form(MultiValueMap<String, Part> form) {
        formReader.requireKnown(form, ImageEditRequests::isRead, name -> name.equals(IMAGE) || name.equals(IMAGE_LIST));
        return new Fields(formReader.text(form, "model"), formReader.text(form, "prompt"), wholeNumber(form, "n"),
                formReader.text(form, "size"), formReader.text(form, "quality"),
                formReader.text(form, "response_format"), bool(form, "async"), formReader.text(form, "webhook_url"),
                formReader.text(form, "gallery_recipe"), formReader.recipe(form));
    }

    /** Whether the route reads a form field of this name. A recipe's bracketed fields are checked where they are read. */
    private static boolean isRead(String name) {
        return TEXT_FIELDS.contains(name) || name.equals(IMAGE) || name.equals(IMAGE_LIST) || name.equals(MASK)
                || StrictRequestReader.isRecipe(name);
    }

    private Integer wholeNumber(MultiValueMap<String, Part> form, String name) {
        String value = formReader.text(form, name);
        if (value == null) return null;
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw ImageEditRefusedException.invalid(name, name + " must be a whole number");
        }
    }

    private Boolean bool(MultiValueMap<String, Part> form, String name) {
        String value = formReader.text(form, name);
        return value == null ? null : Boolean.valueOf(value.trim());
    }

    // ===== Refusals ===================================================

    /** How this route refuses, in JSON or in the form: its own exception, its own words. */
    private record Refusals(boolean inForm) implements StrictRequestReader.Route {

        @Override
        public String name() {
            return ROUTE;
        }

        @Override
        public RuntimeException invalid(String param, String detail) {
            return ImageEditRefusedException.invalid(param, detail);
        }

        @Override
        public RuntimeException unknownField(String field) {
            return ImageEditRefusedException.invalid(field, inForm ? unknownInForm(field) : unknownInJson(field));
        }
    }

    /** A field of the JSON body that the route does not read. */
    private static String unknownInJson(String field) {
        String doesNotTake = ROUTE + " does not take " + field + ". ";
        if (field.equals(IMAGE)) {
            return doesNotTake + "In JSON the images are images, a list of input references: [{\"file_id\": …}] "
                    + "or [{\"job_output\": {\"job_id\": …, \"index\": …}}]. To send the images themselves, send "
                    + "OpenAI's multipart form, with image parts.";
        }
        return doesNotTake + TAKES_JSON;
    }

    /** A field of the form that the route does not read. */
    private static String unknownInForm(String field) {
        String root = StrictRequestReader.root(field);
        String doesNotTake = ROUTE + " does not take " + field + ". ";
        if (root.equals(StrictRequestReader.RECIPE)) return doesNotTake + StrictRequestReader.RECIPE_IN_A_FORM;
        if (root.equals(IMAGE)) {
            return doesNotTake + "In a form, each image is a file part named image, or image[] as OpenAI's SDK "
                    + "names a list of them.";
        }
        if (root.equals("images") || root.equals(MASK)) {
            return doesNotTake + "In a form, the images are file parts named image, and the mask a file part named "
                    + "mask. To edit images that are already stored, send the request as JSON: "
                    + "{\"images\": [{\"file_id\": …}]}.";
        }
        return doesNotTake + TAKES_FORM;
    }
}
