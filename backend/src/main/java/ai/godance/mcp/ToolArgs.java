package ai.godance.mcp;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.ObjectMapper;

/**
 * A tool call's arguments. The MCP server has already checked them against
 * the tool's input schema; these readers add the checks the REST routes
 * make, with the same 400s, so a bad argument is refused before anything is
 * looked up or charged.
 */
final class ToolArgs {

    private final Map<String, Object> values;

    ToolArgs(Map<String, Object> values) {
        this.values = values == null ? Map.of() : values;
    }

    boolean has(String name) {
        return values.get(name) != null;
    }

    /** A text argument, or null when it is absent or blank. */
    String text(String name) {
        Object value = values.get(name);
        if (value == null) return null;
        if (!(value instanceof String text)) throw badRequest(name + " must be text");
        return text.isBlank() ? null : text;
    }

    /** A text argument that must be there, as the REST routes' {@code @NotBlank}. */
    String requiredText(String name) {
        String text = text(name);
        if (text == null) throw badRequest(name + " must not be blank");
        return text;
    }

    /** A whole-number argument, or null when it is absent. */
    Integer integer(String name) {
        Long whole = whole(name);
        if (whole == null) return null;
        if (whole < Integer.MIN_VALUE || whole > Integer.MAX_VALUE) throw badRequest(name + " is out of range");
        return whole.intValue();
    }

    /** A whole number of at least {@code min}, or null when it is absent. */
    Integer integerAtLeast(String name, int min) {
        Integer value = integer(name);
        if (value != null && value < min) throw badRequest(name + " must be at least " + min);
        return value;
    }

    /** {@code max_cost_cents}: a whole number of cents, 0 or more, or null when it is absent. */
    Long maxCostCents() {
        Long cents = whole("max_cost_cents");
        if (cents != null && cents < 0) throw badRequest("max_cost_cents must be 0 or more");
        return cents;
    }

    Boolean bool(String name) {
        Object value = values.get(name);
        if (value == null) return null;
        if (!(value instanceof Boolean flag)) throw badRequest(name + " must be true or false");
        return flag;
    }

    /** An id argument; a malformed one is answered by {@code malformed}, as the REST route answers it. */
    UUID id(String name, Supplier<? extends RuntimeException> malformed) {
        String text = text(name);
        if (text == null) return null;
        try {
            return UUID.fromString(text.trim());
        } catch (IllegalArgumentException e) {
            throw malformed.get();
        }
    }

    /** An argument converted to a REST request type, with the HTTP API's own JSON rules. */
    <T> T as(String name, Class<T> type, ObjectMapper json) {
        Object value = values.get(name);
        if (value == null) return null;
        try {
            return json.convertValue(value, type);
        } catch (RuntimeException e) {
            throw badRequest(name + " is not in the expected shape");
        }
    }

    /** A list argument converted element by element, or an empty list when it is absent. */
    <T> List<T> listOf(String name, Class<T> type, ObjectMapper json) {
        Object value = values.get(name);
        if (value == null) return List.of();
        if (!(value instanceof List<?> list)) throw badRequest(name + " must be an array");
        try {
            return list.stream().map(item -> json.convertValue(item, type)).toList();
        } catch (RuntimeException e) {
            throw badRequest(name + " is not in the expected shape");
        }
    }

    private Long whole(String name) {
        Object value = values.get(name);
        if (value == null) return null;
        if (value instanceof Number number && number.doubleValue() == Math.rint(number.doubleValue())
                && !Double.isInfinite(number.doubleValue())) {
            return number.longValue();
        }
        throw badRequest(name + " must be a whole number");
    }

    static ResponseStatusException badRequest(String detail) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
    }
}
