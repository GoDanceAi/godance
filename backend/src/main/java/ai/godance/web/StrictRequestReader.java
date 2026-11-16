package ai.godance.web;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import org.springframework.http.codec.multipart.FormFieldPart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.util.MultiValueMap;

import ai.godance.web.dto.InputDtos.InputRef;
import ai.godance.web.dto.InputDtos.JobOutputRef;
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
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads a request the way a route that refuses what it does not read needs
 * it read: JSON strictly, and the multipart form OpenAI's SDK sends, in
 * which a nested value arrives as bracketed fields.
 * {@code POST /v1/videos} ({@link OpenAiVideoRequests}) and
 * {@code POST /v1/images/edits} ({@link ImageEditRequests}) read with it,
 * each saying, as a {@link Route}, how it names itself and how it refuses.
 *
 * <p><b>Strictly.</b> A value is read as the record it is, and a field the
 * record does not have is refused rather than skipped, at any depth. The
 * refusal names the field as the caller wrote it: {@code recipe.variable}
 * in JSON, {@code recipe[variable]} in a form's brackets. A field whose
 * value is null carries nothing and passes, as typed clients send every
 * field they know.
 *
 * <p><b>Values in a form.</b> OpenAI's SDK writes a nested value as
 * bracketed fields: {@code extra_body={"recipe": {"version_id": …,
 * "variables": {"subject": …}}}} is sent as {@code recipe[version_id]} and
 * {@code recipe[variables][subject]}. Those fields are read back into the
 * value they were written from. The value as JSON text
 * ({@code json.dumps(…)}) is taken too. Read either way, it is then checked
 * exactly as the same value in JSON is.
 */
final class StrictRequestReader {

    /** How a route names itself and refuses: its own exception, its own words for a field it does not read. */
    interface Route {

        /** The route as a refusal names it: {@code POST /v1/videos}. */
        String name();

        /** 400 {@code invalid_parameter}; {@code param} names the field, or is null for the request as a whole. */
        RuntimeException invalid(String param, String detail);

        /** A field of the request itself that the route does not read, named as the caller wrote it. */
        RuntimeException unknownField(String field);

        /** A form field the route reads as text, sent as a file. */
        default RuntimeException notText(String field) {
            return invalid(field, field + " must be a text field");
        }
    }

    /** The Godance extension both routes take in place of {@code prompt}. */
    static final String RECIPE = "recipe";

    /** What {@code recipe} must be as JSON text. */
    static final String RECIPE_SHAPE = "a JSON object: {\"version_id\": …, \"variables\": {…}}";

    /** How {@code recipe} goes in a form, for a refusal of a field that is no part of it. */
    static final String RECIPE_IN_A_FORM = "In a form, recipe is JSON text, or recipe[version_id] with "
            + "recipe[variables][name] and recipe[inputs][slot][file_id] fields.";

    /** A field set to null carries nothing: passing over it drops nothing. */
    private static final DeserializationProblemHandler NULL_CARRIES_NOTHING = new DeserializationProblemHandler() {
        @Override
        public boolean handleUnknownProperty(DeserializationContext ctxt, JsonParser p,
                                             ValueDeserializer<?> deserializer, Object beanOrClass, String propertyName) {
            return p.currentToken() == JsonToken.VALUE_NULL;
        }
    };

    private final ObjectMapper json;
    private final Route route;
    /** The HTTP mapper's reading, except that a field no record has is an error rather than skipped. */
    private final ObjectReader strict;
    private final JavaType recipeRef;

    StrictRequestReader(ObjectMapper json, Route route) {
        this.json = json;
        this.route = route;
        this.strict = json.reader()
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .withHandler(NULL_CARRIES_NOTHING);
        this.recipeRef = type(RecipeRef.class);
    }

    /** A value's type, as {@link #read} and the form's values take it. */
    JavaType type(Class<?> type) {
        return json.getTypeFactory().constructType(type);
    }

    /** A list of values of this type. */
    JavaType listOf(Class<?> element) {
        return json.getTypeFactory().constructCollectionType(List.class, element);
    }

    // ===== JSON =======================================================

    /** The request body as the record it is, with every field read or refused. */
    <T> T body(JsonNode body, JavaType type) {
        if (body == null || !body.isObject()) throw route.invalid(null, "The request body must be a JSON object.");
        return read(body, type, "", false);
    }

    /**
     * The value as the record it is, with a field the record does not have
     * refused rather than skipped. {@code name} is the field the value came
     * in (empty for the request body itself); {@code brackets} says whether
     * the caller wrote it as bracketed form fields, which is how a refusal
     * names the field.
     */
    <T> T read(JsonNode value, JavaType type, String name, boolean brackets) {
        try {
            return strict.forType(type).readValue(value);
        } catch (UnrecognizedPropertyException e) {
            throw unrecognized(e, name, brackets);
        } catch (JacksonException e) {
            throw mistyped(e, name, brackets);
        }
    }

    private RuntimeException unrecognized(UnrecognizedPropertyException e, String name, boolean brackets) {
        List<Reference> path = e.getPath();
        String field = name(name, path, brackets);
        if (name.isEmpty() && path.size() == 1) return route.unknownField(field);
        String parent = name(name, path.subList(0, path.size() - 1), brackets);
        return route.invalid(field, route.name() + " does not take " + field + ": "
                + parent + " has " + listed(e.getKnownPropertyIds()) + ".");
    }

    private RuntimeException mistyped(JacksonException e, String name, boolean brackets) {
        String field = name(name, e.getPath(), brackets);
        if (field.isEmpty()) return route.invalid(null, "The request body must be a JSON object.");
        Class<?> target = e instanceof MismatchedInputException mismatch ? mismatch.getTargetType() : null;
        String expected = expected(target);
        return route.invalid(field, expected == null
                ? field + " has a value " + route.name() + " cannot read."
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
        if (type == RecipeRef.class) return RECIPE_SHAPE;
        if (type == UUID.class) return "an id";
        if (type == Integer.class || type == int.class) return "a whole number";
        if (type == Boolean.class || type == boolean.class) return "true or false";
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

    /** {@code a, b and c}. */
    private static String listed(Collection<Object> names) {
        List<String> all = names == null ? List.of() : names.stream().map(String::valueOf).toList();
        if (all.isEmpty()) return "no fields";
        if (all.size() == 1) return all.get(0);
        return String.join(", ", all.subList(0, all.size() - 1)) + " and " + all.get(all.size() - 1);
    }

    // ===== The multipart form =========================================

    /**
     * Refuses a field the route does not read, and a field sent more than
     * once that the route reads once: only one of the two could be read.
     * A field whose value comes once per element of a list {@code repeats}.
     */
    void requireKnown(MultiValueMap<String, Part> form, Predicate<String> read, Predicate<String> repeats) {
        for (Map.Entry<String, List<Part>> field : form.entrySet()) {
            String name = field.getKey();
            if (!read.test(name)) throw route.unknownField(name);
            int times = field.getValue().size();
            if (times > 1 && !repeats.test(name)) {
                throw route.invalid(name, "send " + name + " once: the form has it " + times + " times");
            }
        }
    }

    /**
     * Whether a form field is {@code recipe} or one of its bracketed fields.
     * Those are checked where they are read, against the fields a recipe has.
     */
    static boolean isRecipe(String field) {
        return root(field).equals(RECIPE);
    }

    /** {@code recipe}: its bracketed fields, or the value as JSON text. Null when the form has neither. */
    RecipeRef recipe(MultiValueMap<String, Part> form) {
        return objectValue(form, RECIPE, recipeRef, RECIPE_SHAPE);
    }

    /**
     * A value that is an object: its bracketed fields {@code name[…]}, or
     * the value as JSON text, which must be {@code shape}. Null when the
     * form has neither.
     */
    <T> T objectValue(MultiValueMap<String, Part> form, String name, JavaType type, String shape) {
        return value(form, name, objectFields(form, name), name + "[…]", type, shape);
    }

    /**
     * A value sent as JSON text, which must be {@code shape}, or as
     * bracketed fields written as {@code brackets} says, not both.
     * {@code bracketed} is the value rebuilt from those fields, null when
     * the form has none of them. Null when the form has neither.
     */
    <T> T value(MultiValueMap<String, Part> form, String name, JsonNode bracketed, String brackets,
                JavaType type, String shape) {
        String text = text(form, name);
        if (text != null && bracketed != null) {
            throw route.invalid(name, "send " + name + " once: as JSON text, or as " + brackets + " fields, not both");
        }
        if (bracketed != null) return read(bracketed, type, name, true);
        if (text == null) return null;
        JsonNode parsed;
        try {
            parsed = json.readTree(text);
        } catch (JacksonException e) {
            throw route.invalid(name, name + " must be " + shape);
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
    ObjectNode objectFields(MultiValueMap<String, Part> form, String name) {
        ObjectNode value = null;
        for (String field : form.keySet()) {
            if (!field.startsWith(name + "[")) continue;
            List<String> path = path(field, name.length());
            // Brackets that do not close, and a list where the value has none, are no field of the route.
            if (path == null || path.contains("")) throw route.unknownField(field);
            if (value == null) value = json.createObjectNode();
            put(value, path, rawText(form, field), name, field);
        }
        return value;
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
    private void put(ObjectNode value, List<String> path, String text, String name, String field) {
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

    private RuntimeException sentBothWays(String field, String inside) {
        return route.invalid(field, "the form sends " + field + " as a value and as fields inside it"
                + (inside == null ? "" : ", such as " + inside) + "; send one or the other");
    }

    /** A text field's value, or null when the form has no such field or it is empty. */
    String text(MultiValueMap<String, Part> form, String name) {
        Part part = form.getFirst(name);
        if (part == null) return null;
        String value = textOf(part, name);
        return value == null || value.isBlank() ? null : value;
    }

    /** A text field's value as it was sent, an empty one included. */
    String rawText(MultiValueMap<String, Part> form, String name) {
        return textOf(form.getFirst(name), name);
    }

    /** Every value of a field that may come more than once, in the order sent. */
    List<String> rawTexts(MultiValueMap<String, Part> form, String name) {
        List<Part> parts = form.get(name);
        if (parts == null) return List.of();
        List<String> values = new ArrayList<>(parts.size());
        for (Part part : parts) values.add(textOf(part, name));
        return values;
    }

    private String textOf(Part part, String name) {
        if (part instanceof FormFieldPart field) return field.value();
        throw route.notText(name);
    }

    /** A field's name before its first bracket: {@code recipe} for {@code recipe[version_id]}. */
    static String root(String field) {
        int bracket = field.indexOf('[');
        return bracket < 0 ? field : field.substring(0, bracket);
    }
}
