package ai.godance.web;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.codec.multipart.Part;
import org.springframework.util.MultiValueMap;

import ai.godance.service.InputRefusedException;
import ai.godance.service.OpenAiVideoRefusedException;
import ai.godance.web.dto.InputDtos.InputRef;
import ai.godance.web.dto.InputDtos.JobOutputRef;
import ai.godance.web.dto.OpenAiVideoDtos.CreateRequest;
import ai.godance.web.dto.OpenAiVideoDtos.InputReference;
import ai.godance.web.dto.RecipeDtos.RecipeRef;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A {@code POST /v1/videos} request as the route reads it, from JSON or from
 * the multipart form OpenAI's SDK sends. What the route would not use is
 * refused, never dropped; how the request is read strictly, and how the
 * form's bracketed fields are read back into the values they were written
 * from, is {@link StrictRequestReader}'s.
 *
 * <p><b>Nothing is dropped without a word.</b> A field the route does not
 * read answers 400 {@code invalid_parameter}, its {@code param} naming the
 * field, at any depth: {@code n}, {@code colour}, {@code recipe.variable}.
 * In JSON a field whose value is null carries nothing and passes, as typed
 * clients send every field they know. A form field sent twice is refused,
 * since only one of the two could be read.
 *
 * <p><b>Values in a form.</b> OpenAI's SDK writes a nested value as
 * bracketed fields, the way {@code input_reference[file_id]} has always
 * arrived: {@code extra_body={"recipe": {"version_id": …, "variables":
 * {"subject": …}}}} is sent as {@code recipe[version_id]} and
 * {@code recipe[variables][subject]}, and a list as one field per element,
 * {@code reference_images[][file_id]}. Those fields are read back into the
 * value they were written from. The value as JSON text
 * ({@code json.dumps(…)}) is taken too. Read either way, it is then checked
 * exactly as the same value in JSON is.
 *
 * <p>The SDK writes a list's fields grouped by name, so a list whose
 * elements have different fields loses its order on the way:
 * {@code reference_images} mixing {@code file_id} and {@code job_output}
 * is refused in brackets, and taken as JSON text.
 */
final class OpenAiVideoRequests {

    /**
     * The fields of a create request, parsed. {@code input_reference} as a
     * file part is not among them: the route stores that image only after
     * every check that can be made without it.
     */
    record Fields(String model, String prompt, Integer seconds, String size, String webhookUrl,
                  String galleryRecipe, RecipeRef recipe, InputReference inputReference,
                  InputRef lastFrame, List<InputRef> referenceImages) { }

    static final String INPUT_REFERENCE = "input_reference";
    private static final String ROUTE = "POST /v1/videos";
    private static final String RECIPE = StrictRequestReader.RECIPE;
    private static final String LAST_FRAME = "last_frame";
    private static final String REFERENCE_IMAGES = "reference_images";

    /** The form's fields that are plain text. */
    private static final Set<String> TEXT_FIELDS =
            Set.of("model", "prompt", "seconds", "size", "webhook_url", "gallery_recipe");

    /** {@code input_reference} as an object, the way OpenAI's SDK writes it into a form. */
    private static final Set<String> INPUT_REFERENCE_FIELDS = Set.of("input_reference[file_id]",
            "input_reference[image_url]", "input_reference[job_output][job_id]", "input_reference[job_output][index]");

    /** The Godance extensions that are an object or a list: in a form, JSON text or bracketed fields. */
    private static final Set<String> VALUE_FIELDS = Set.of(RECIPE, LAST_FRAME, REFERENCE_IMAGES);

    /** {@code reference_images} the way OpenAI's SDK writes a list of input references into a form. */
    private static final String REFERENCE_FILE_ID = "reference_images[][file_id]";
    private static final String REFERENCE_JOB_ID = "reference_images[][job_output][job_id]";
    private static final String REFERENCE_INDEX = "reference_images[][job_output][index]";
    private static final Set<String> REFERENCE_FIELDS = Set.of(REFERENCE_FILE_ID, REFERENCE_JOB_ID, REFERENCE_INDEX);

    /**
     * The fields named in a refusal. {@code gallery_recipe} is read but not
     * named: it is for the gallery, which the published docs leave out
     * while it may be switched off.
     */
    private static final String TAKES = "It takes model, prompt, seconds, size and input_reference, and the Godance "
            + "extensions last_frame, reference_images, recipe and webhook_url.";

    /** How this route refuses: its own exception, and its own words for what it does not read. */
    private static final StrictRequestReader.Route REFUSALS = new StrictRequestReader.Route() {
        @Override
        public String name() {
            return ROUTE;
        }

        @Override
        public RuntimeException invalid(String param, String detail) {
            return OpenAiVideoRefusedException.invalid(param, detail);
        }

        @Override
        public RuntimeException unknownField(String field) {
            return OpenAiVideoRequests.unknownField(field);
        }

        @Override
        public RuntimeException notText(String field) {
            String root = StrictRequestReader.root(field);
            if (!root.equals(LAST_FRAME) && !root.equals(REFERENCE_IMAGES)) {
                return StrictRequestReader.Route.super.notText(field);
            }
            return invalid(field, field + " cannot be a file. Upload the image with POST /v1/files and send its id: "
                    + (root.equals(LAST_FRAME) ? "last_frame[file_id]" : REFERENCE_FILE_ID) + ".");
        }
    };

    private final ObjectMapper json;
    private final StrictRequestReader reader;
    private final JavaType createRequest;
    private final JavaType inputRef;
    private final JavaType inputRefs;

    OpenAiVideoRequests(ObjectMapper json) {
        this.json = json;
        this.reader = new StrictRequestReader(json, REFUSALS);
        this.createRequest = reader.type(CreateRequest.class);
        this.inputRef = reader.type(InputRef.class);
        this.inputRefs = reader.listOf(InputRef.class);
    }

    // ===== JSON =======================================================

    /** The JSON request body, with every field it has read or refused. */
    Fields json(JsonNode body) {
        CreateRequest request = reader.body(body, createRequest);
        return new Fields(request.model(), request.prompt(), seconds(request.seconds()), request.size(),
                request.webhookUrl(), request.galleryRecipe(), request.recipe(), request.inputReference(),
                request.lastFrame(), request.referenceImages());
    }

    /** {@code seconds} in JSON: OpenAI's string ({@code "8"}) or a whole number. Null when left out. */
    private static Integer seconds(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isString()) return seconds(node.asString());
        if (node.isIntegralNumber() && node.canConvertToInt()) return node.asInt();
        throw invalidSeconds();
    }

    private static Integer seconds(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return Integer.valueOf(text.trim());
        } catch (NumberFormatException e) {
            throw invalidSeconds();
        }
    }

    private static OpenAiVideoRefusedException invalidSeconds() {
        return OpenAiVideoRefusedException.invalid("seconds",
                "seconds must be a whole number of seconds, as a string (\"8\") or a number (8)");
    }

    // ===== The multipart form =========================================

    /**
     * The form's fields, every one of them read or refused. The
     * {@code input_reference} file part is left to the caller.
     */
    Fields form(MultiValueMap<String, Part> form) {
        // A list's fields come once per element; every other field once.
        reader.requireKnown(form, OpenAiVideoRequests::isRead, REFERENCE_FIELDS::contains);
        return new Fields(reader.text(form, "model"), reader.text(form, "prompt"), seconds(reader.text(form, "seconds")),
                reader.text(form, "size"), reader.text(form, "webhook_url"), reader.text(form, "gallery_recipe"),
                reader.recipe(form), referenceOf(form),
                reader.objectValue(form, LAST_FRAME, inputRef, shapeOf(LAST_FRAME)),
                reader.value(form, REFERENCE_IMAGES, referenceImageFields(form), "reference_images[][…]",
                        inputRefs, shapeOf(REFERENCE_IMAGES)));
    }

    /**
     * Whether the route reads a form field of this name. The bracketed
     * fields of {@code recipe} and {@code last_frame} are checked where they
     * are read, against the fields the value has.
     */
    private static boolean isRead(String name) {
        if (TEXT_FIELDS.contains(name) || VALUE_FIELDS.contains(name) || REFERENCE_FIELDS.contains(name)) return true;
        if (name.equals(INPUT_REFERENCE) || INPUT_REFERENCE_FIELDS.contains(name)) return true;
        return StrictRequestReader.isRecipe(name) || StrictRequestReader.root(name).equals(LAST_FRAME);
    }

    /**
     * {@code input_reference} as an object arrives in a form the way
     * OpenAI's SDK writes it: {@code input_reference[file_id]},
     * {@code input_reference[image_url]}, and for the Godance extension
     * {@code input_reference[job_output][job_id]} and {@code […][index]}.
     * Null when the form has none of them.
     */
    private InputReference referenceOf(MultiValueMap<String, Part> form) {
        String fileId = reader.text(form, "input_reference[file_id]");
        String imageUrl = reader.text(form, "input_reference[image_url]");
        String jobId = reader.text(form, "input_reference[job_output][job_id]");
        String index = reader.text(form, "input_reference[job_output][index]");
        if (fileId == null && imageUrl == null && jobId == null && index == null) return null;
        JobOutputRef jobOutput = null;
        if (jobId != null || index != null) {
            try {
                jobOutput = new JobOutputRef(jobId == null ? null : UUID.fromString(jobId.trim()),
                        index == null ? null : Integer.valueOf(index.trim()));
            } catch (IllegalArgumentException e) {
                throw InputRefusedException.jobOutputNotFound();
            }
        }
        return new InputReference(fileId, imageUrl, jobOutput);
    }

    /**
     * {@code reference_images} as OpenAI's SDK writes a list of input
     * references into a form, one field per element:
     * {@code reference_images[][file_id]}, or
     * {@code reference_images[][job_output][job_id]} with
     * {@code […][index]}. The SDK sends a list's fields grouped by name, so
     * the elements can be put back in order only when each has the same
     * fields. Null when the form has none of them.
     */
    private ArrayNode referenceImageFields(MultiValueMap<String, Part> form) {
        List<String> fileIds = reader.rawTexts(form, REFERENCE_FILE_ID);
        List<String> jobIds = reader.rawTexts(form, REFERENCE_JOB_ID);
        List<String> indexes = reader.rawTexts(form, REFERENCE_INDEX);
        if (fileIds.isEmpty() && jobIds.isEmpty() && indexes.isEmpty()) return null;
        if (!fileIds.isEmpty() && !(jobIds.isEmpty() && indexes.isEmpty())) {
            throw OpenAiVideoRefusedException.invalid(REFERENCE_IMAGES, "reference_images mixes file_id and "
                    + "job_output: in a form, OpenAI's SDK sends a list's fields grouped by name, so the images would "
                    + "arrive out of order. Send reference_images as JSON text instead, in the order you mean.");
        }
        if (jobIds.size() != indexes.size()) {
            throw OpenAiVideoRefusedException.invalid(REFERENCE_IMAGES, "each reference image that is a job_output "
                    + "needs both reference_images[][job_output][job_id] and reference_images[][job_output][index]; "
                    + "the form has " + jobIds.size() + " job_id and " + indexes.size() + " index fields");
        }
        ArrayNode list = json.createArrayNode();
        for (String fileId : fileIds) list.addObject().put("file_id", fileId);
        for (int i = 0; i < jobIds.size(); i++) {
            ObjectNode output = list.addObject().putObject("job_output");
            output.put("job_id", jobIds.get(i));
            output.put("index", indexes.get(i));
        }
        return list;
    }

    private static String shapeOf(String name) {
        return switch (name) {
            case RECIPE -> StrictRequestReader.RECIPE_SHAPE;
            case REFERENCE_IMAGES -> "a JSON list of input references: [{\"file_id\": …}, …]";
            default -> "a JSON object: {\"file_id\": …} or {\"job_output\": {\"job_id\": …, \"index\": …}}";
        };
    }

    /** A field of the request itself that the route does not read. */
    private static OpenAiVideoRefusedException unknownField(String field) {
        String root = StrictRequestReader.root(field);
        String detail;
        if (field.equals("n")) {
            detail = "POST /v1/videos makes one video per request and takes no n. To make several clips from one "
                    + "request, use POST /v1/videos/generations.";
        } else if (field.equals("first_frame")) {
            detail = "POST /v1/videos takes the first frame as input_reference: send input_reference in place of "
                    + "first_frame.";
        } else if (root.equals(INPUT_REFERENCE)) {
            detail = "POST /v1/videos does not take " + field + ". In a form, input_reference is a file, "
                    + "input_reference[file_id], or input_reference[job_output][job_id] with "
                    + "input_reference[job_output][index].";
        } else if (root.equals(REFERENCE_IMAGES)) {
            detail = "POST /v1/videos does not take " + field + ". In a form, reference_images is JSON text, or one "
                    + REFERENCE_FILE_ID + " field per image, or one " + REFERENCE_JOB_ID + " and one "
                    + REFERENCE_INDEX + " per image.";
        } else if (root.equals(LAST_FRAME)) {
            detail = "POST /v1/videos does not take " + field + ". In a form, last_frame is JSON text, "
                    + "last_frame[file_id], or last_frame[job_output][job_id] with last_frame[job_output][index].";
        } else if (root.equals(RECIPE)) {
            detail = "POST /v1/videos does not take " + field + ". " + StrictRequestReader.RECIPE_IN_A_FORM;
        } else {
            detail = "POST /v1/videos does not take " + field + ". " + TAKES;
        }
        return OpenAiVideoRefusedException.invalid(field, detail);
    }
}
