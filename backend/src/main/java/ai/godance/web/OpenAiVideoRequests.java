package ai.godance.web;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.codec.multipart.FormFieldPart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.util.MultiValueMap;

import ai.godance.service.InputRefusedException;
import ai.godance.service.OpenAiVideoRefusedException;
import ai.godance.web.dto.InputDtos.InputRef;
import ai.godance.web.dto.InputDtos.JobOutputRef;
import ai.godance.web.dto.OpenAiVideoDtos.CreateRequest;
import ai.godance.web.dto.OpenAiVideoDtos.InputReference;
import ai.godance.web.dto.RecipeDtos.RecipeRef;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JacksonException.Reference;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.deser.DeserializationProblemHandler;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A {@code POST /v1/videos} request as the route reads it, from JSON or from
 * the multipart form OpenAI's SDK sends. What the route would not use is
 * refused, never dropped.
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
    private static final String RECIPE = "recipe";
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

    /** A field set to null carries nothing: passing over it drops nothing. */
    private static final DeserializationProblemHandler NULL_CARRIES_NOTHING = new DeserializationProblemHandler() {
        @Override
        public boolean handleUnknownProperty(DeserializationContext ctxt, JsonParser p,
                                             ValueDeserializer<?> deserializer, Object beanOrClass, String propertyName) {
            return p.currentToken() == JsonToken.VALUE_NULL;
        }
    };

    private final ObjectMapper json;
    /** The HTTP mapper's reading, except that a field no record has is an error rather than skipped. */
    private final ObjectReader strict;
    private final JavaType createRequest;
    private final JavaType recipeRef;
    private final JavaType inputRef;
    private final JavaType inputRefs;

    OpenAiVideoRequests(ObjectMapper json) {
        this.json = json;
        this.strict = json.reader()
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .withHandler(NULL_CARRIES_NOTHING);
        this.createRequest = json.getTypeFactory().constructType(CreateRequest.class);
        this.recipeRef = json.getTypeFactory().constructType(RecipeRef.class);
        this.inputRef = json.getTypeFactory().constructType(InputRef.class);
        this.inputRefs = json.getTypeFactory().constructCollectionType(List.class, InputRef.class);
    }

    // ===== JSON =======================================================

    /** The JSON request body, with every field it has read or refused. */
    Fields json(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw OpenAiVideoRefusedException.invalid(null, "The request body must be a JSON object.");
        }
        CreateRequest request = read(body, createRequest, "", false);
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
        requireKnown(form);
        return new Fields(text(form, "model"), text(form, "prompt"), seconds(text(form, "seconds")),
                text(form, "size"), text(form, "webhook_url"), text(form, "gallery_recipe"),
                value(form, RECIPE, recipeRef), referenceOf(form),
                value(form, LAST_FRAME, inputRef), value(form, REFERENCE_IMAGES, inputRefs));
    }

    /** Refuses a field the route would not read, and a field sent more than once. */
    private static void requireKnown(MultiValueMap<String, Part> form) {
        for (Map.Entry<String, List<Part>> field : form.entrySet()) {
            String name = field.getKey();
            if (!isRead(name)) throw unknownField(name);
            // A list's fields come once per element; every other field once.
            if (field.getValue().size() > 1 && !REFERENCE_FIELDS.contains(name)) {
                throw OpenAiVideoRefusedException.invalid(name, "send " + name + " once: the form has it "
                        + field.getValue().size() + " times");
            }
        }
    }

    /**
     * Whether the route reads a form field of this name. The bracketed
     * fields of {@code recipe} and {@code last_frame} are checked where they
     * are read, against the fields the value has.
     */
    private static boolean isRead(String name) {
        if (TEXT_FIELDS.contains(name) || VALUE_FIELDS.contains(name) || REFERENCE_FIELDS.contains(name)) return true;
        if (name.equals(INPUT_REFERENCE) || INPUT_REFERENCE_FIELDS.contains(name)) return true;
        String root = root(name);
        return root.equals(RECIPE) || root.equals(LAST_FRAME);
    }

    /**
     * {@code input_reference} as an object arrives in a form the way
     * OpenAI's SDK writes it: {@code input_reference[file_id]},
     * {@code input_reference[image_url]}, and for the Godance extension
     * {@code input_reference[job_output][job_id]} and {@code […][index]}.
     * Null when the form has none of them.
     */
    private static InputReference referenceOf(MultiValueMap<String, Part> form) {
        String fileId = text(form, "input_reference[file_id]");
        String imageUrl = text(form, "input_reference[image_url]");
        String jobId = text(form, "input_reference[job_output][job_id]");
        String index = text(form, "input_reference[job_output][index]");
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
     * {@code recipe}, {@code last_frame} or {@code reference_images}: its
     * bracketed fields, or the value as JSON text. Null when the form has
     * neither.
     */
    private <T> T value(MultiValueMap<String, Part> form, String name, JavaType type) {
        String text = text(form, name);
        JsonNode fields = name.equals(REFERENCE_IMAGES) ? referenceImageFields(form) : objectFields(form, name);
        if (text != null && fields != null) {
            throw OpenAiVideoRefusedException.invalid(name, "send " + name + " once: as JSON text, or as "
                    + bracketsOf(name) + " fields, not both");
        }
        if (fields != null) return read(fields, type, name, true);
        if (text == null) return null;
        JsonNode parsed;
        try {
            parsed = json.readTree(text);
        } catch (JacksonException e) {
            throw OpenAiVideoRefusedException.invalid(name, name + " must be " + shapeOf(name));
        }
        return read(parsed, type, name, false);
    }

    /**
     * The fields {@code name[…]…} of the form, as the JSON object OpenAI's
     * SDK flattened into them: {@code recipe[version_id]} and
     * {@code recipe[variables][subject]} are {@code {"version_id": …,
     * "variables": {"subject": …}}}. Each value is the text sent, an empty
     * one included. Null when the form has none.
     */
    private ObjectNode objectFields(MultiValueMap<String, Part> form, String name) {
        ObjectNode value = null;
        for (String field : form.keySet()) {
            if (!field.startsWith(name + "[")) continue;
            List<String> path = path(field, name.length());
            // Brackets that do not close, and a list where the value has none, are no field of this route.
            if (path == null || path.contains("")) throw unknownField(field);
            if (value == null) value = json.createObjectNode();
            put(value, path, rawText(form, field), name, field);
        }
        return value;
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
        List<String> fileIds = rawTexts(form, REFERENCE_FILE_ID);
        List<String> jobIds = rawTexts(form, REFERENCE_JOB_ID);
        List<String> indexes = rawTexts(form, REFERENCE_INDEX);
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

    /** {@code [a][b]} after a field's name, as {@code [a, b]}; null when the brackets are not that shape. */
    private static List<String> path(String field, int from) {
        List<String> path = new ArrayList<>();
        int at = from;
        while (at < field.length()) {
            int close = field.indexOf(']', at);
            if (field.charAt(at) != '[' || close < 0) return null;
            path.add(field.substring(at + 1, close));
            at = close + 1;
        }
        return path.isEmpty() ? null : path;
    }

    /** Sets the value at its path, refusing a path that is also a value of its own. */
    private static void put(ObjectNode value, List<String> path, String text, String name, String field) {
        ObjectNode at = value;
        StringBuilder reached = new StringBuilder(name);
        for (String step : path.subList(0, path.size() - 1)) {
            reached.append('[').append(step).append(']');
            JsonNode next = at.get(step);
            if (next == null) {
                next = at.putObject(step);
            } else if (!next.isObject()) {
                throw sentBothWays(reached.toString(), field);
            }
            at = (ObjectNode) next;
        }
        String last = path.get(path.size() - 1);
        if (at.has(last)) throw sentBothWays(field, null);
        at.put(last, text);
    }

    private static OpenAiVideoRefusedException sentBothWays(String field, String inside) {
        return OpenAiVideoRefusedException.invalid(field, "the form sends " + field + " as a value and as fields "
                + "inside it" + (inside == null ? "" : ", such as " + inside) + "; send one or the other");
    }

    /** A text field's value, or null when the form has no such field or it is empty. */
    private static String text(MultiValueMap<String, Part> form, String name) {
        Part part = form.getFirst(name);
        if (part == null) return null;
        String value = textOf(part, name);
        return value == null || value.isBlank() ? null : value;
    }

    /** A text field's value as it was sent, an empty one included. */
    private static String rawText(MultiValueMap<String, Part> form, String name) {
        return textOf(form.getFirst(name), name);
    }

    /** Every value of a field that may come more than once, in the order sent. */
    private static List<String> rawTexts(MultiValueMap<String, Part> form, String name) {
        List<Part> parts = form.get(name);
        if (parts == null) return List.of();
        List<String> values = new ArrayList<>(parts.size());
        for (Part part : parts) values.add(textOf(part, name));
        return values;
    }

    private static String textOf(Part part, String name) {
        if (part instanceof FormFieldPart field) return field.value();
        String root = root(name);
        if (root.equals(LAST_FRAME) || root.equals(REFERENCE_IMAGES)) {
            throw OpenAiVideoRefusedException.invalid(name, name + " cannot be a file. Upload the image with "
                    + "POST /v1/files and send its id: " + (root.equals(LAST_FRAME)
                    ? "last_frame[file_id]" : REFERENCE_FILE_ID) + ".");
        }
        throw OpenAiVideoRefusedException.invalid(name, name + " must be a text field");
    }

    /** A field's name before its first bracket: {@code recipe} for {@code recipe[version_id]}. */
    private static String root(String field) {
        int bracket = field.indexOf('[');
        return bracket < 0 ? field : field.substring(0, bracket);
    }

    private static String bracketsOf(String name) {
        return name.equals(REFERENCE_IMAGES) ? "reference_images[][…]" : name + "[…]";
    }

    private static String shapeOf(String name) {
        return switch (name) {
            case RECIPE -> "a JSON object: {\"version_id\": …, \"variables\": {…}}";
            case REFERENCE_IMAGES -> "a JSON list of input references: [{\"file_id\": …}, …]";
            default -> "a JSON object: {\"file_id\": …} or {\"job_output\": {\"job_id\": …, \"index\": …}}";
        };
    }

    // ===== Reading a value strictly ===================================

    /**
     * The value as the record it is, with a field the record does not have
     * refused rather than skipped. {@code name} is the field the value came
     * in (empty for the request body itself); {@code brackets} says whether
     * the caller wrote it as bracketed form fields, which is how a refusal
     * names the field.
     */
    private <T> T read(JsonNode value, JavaType type, String name, boolean brackets) {
        try {
            return strict.forType(type).readValue(value);
        } catch (UnrecognizedPropertyException e) {
            throw unrecognized(e, name, brackets);
        } catch (JacksonException e) {
            throw mistyped(e, name, brackets);
        }
    }

    private static OpenAiVideoRefusedException unrecognized(UnrecognizedPropertyException e, String name,
                                                            boolean brackets) {
        List<Reference> path = e.getPath();
        String field = name(name, path, brackets);
        if (name.isEmpty() && path.size() == 1) return unknownField(field);
        String parent = name(name, path.subList(0, path.size() - 1), brackets);
        return OpenAiVideoRefusedException.invalid(field, "POST /v1/videos does not take " + field + ": "
                + parent + " has " + listed(e.getKnownPropertyIds()) + ".");
    }

    private static OpenAiVideoRefusedException mistyped(JacksonException e, String name, boolean brackets) {
        String field = name(name, e.getPath(), brackets);
        if (field.isEmpty()) {
            return OpenAiVideoRefusedException.invalid(null, "The request body must be a JSON object.");
        }
        Class<?> target = e instanceof MismatchedInputException mismatch ? mismatch.getTargetType() : null;
        String expected = expected(target);
        return OpenAiVideoRefusedException.invalid(field, expected == null
                ? field + " has a value POST /v1/videos cannot read."
                : field + " must be " + expected + ".");
    }

    /** What a value of this type is, for a person: "an id", "a list". Null for a type it has no words for. */
    private static String expected(Class<?> type) {
        if (type == null) return null;
        if (type == InputRef.class) {
            return "an input reference: {\"file_id\": …} or {\"job_output\": {\"job_id\": …, \"index\": …}}";
        }
        if (type == InputReference.class) {
            return "an object: {\"file_id\": …} or {\"job_output\": {\"job_id\": …, \"index\": …}}";
        }
        if (type == JobOutputRef.class) return "an object: {\"job_id\": …, \"index\": …}";
        if (type == RecipeRef.class) return shapeOf(RECIPE);
        if (type == UUID.class) return "an id";
        if (type == Integer.class || type == int.class) return "a whole number";
        if (type == String.class) return "a string";
        if (Collection.class.isAssignableFrom(type)) return "a list";
        if (Map.class.isAssignableFrom(type) || Record.class.isAssignableFrom(type)) return "an object";
        return null;
    }

    /**
     * A field's name as the caller wrote it: {@code recipe.variables} or
     * {@code reference_images[1].file_id} in JSON, {@code recipe[variables]}
     * or {@code reference_images[][file_id]} in a form's brackets.
     */
    private static String name(String name, List<Reference> path, boolean brackets) {
        StringBuilder field = new StringBuilder(name);
        for (Reference step : path) {
            String property = step.getPropertyName();
            if (brackets) {
                field.append('[').append(property == null ? "" : property).append(']');
            } else if (property == null) {
                field.append('[').append(step.getIndex()).append(']');
            } else {
                if (!field.isEmpty()) field.append('.');
                field.append(property);
            }
        }
        return field.toString();
    }

    /** A field of the request itself that the route does not read. */
    private static OpenAiVideoRefusedException unknownField(String field) {
        String detail;
        if (field.equals("n")) {
            detail = "POST /v1/videos makes one video per request and takes no n. To make several clips from one "
                    + "request, use POST /v1/videos/generations.";
        } else if (field.equals("first_frame")) {
            detail = "POST /v1/videos takes the first frame as input_reference: send input_reference in place of "
                    + "first_frame.";
        } else if (root(field).equals(INPUT_REFERENCE)) {
            detail = "POST /v1/videos does not take " + field + ". In a form, input_reference is a file, "
                    + "input_reference[file_id], or input_reference[job_output][job_id] with "
                    + "input_reference[job_output][index].";
        } else if (root(field).equals(REFERENCE_IMAGES)) {
            detail = "POST /v1/videos does not take " + field + ". In a form, reference_images is JSON text, or one "
                    + REFERENCE_FILE_ID + " field per image, or one " + REFERENCE_JOB_ID + " and one "
                    + REFERENCE_INDEX + " per image.";
        } else if (root(field).equals(LAST_FRAME)) {
            detail = "POST /v1/videos does not take " + field + ". In a form, last_frame is JSON text, "
                    + "last_frame[file_id], or last_frame[job_output][job_id] with last_frame[job_output][index].";
        } else if (root(field).equals(RECIPE)) {
            detail = "POST /v1/videos does not take " + field + ". In a form, recipe is JSON text, or "
                    + "recipe[version_id] with recipe[variables][name] and recipe[inputs][slot][file_id] fields.";
        } else {
            detail = "POST /v1/videos does not take " + field + ". " + TAKES;
        }
        return OpenAiVideoRefusedException.invalid(field, detail);
    }

    /** {@code a, b and c}. */
    private static String listed(Collection<Object> names) {
        List<String> all = names == null ? List.of() : names.stream().map(String::valueOf).toList();
        if (all.isEmpty()) return "no fields";
        if (all.size() == 1) return all.get(0);
        return String.join(", ", all.subList(0, all.size() - 1)) + " and " + all.get(all.size() - 1);
    }
}
