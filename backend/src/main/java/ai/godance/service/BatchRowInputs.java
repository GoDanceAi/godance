package ai.godance.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import ai.godance.web.dto.InputDtos.InputRef;

/**
 * How a batch row keeps the input named for each of its recipe's slots:
 * as the request wrote it, {@code {"file_id": "…"}} or
 * {@code {"job_output": {"job_id": "…", "index": 0}}}, by slot name. Only
 * references are stored, never an image.
 */
final class BatchRowInputs {

    private BatchRowInputs() { }

    /** A reference, as the row stores it. */
    static Map<String, Object> toStored(InputRef ref) {
        Map<String, Object> stored = new LinkedHashMap<>();
        if (ref.fileId() != null) {
            stored.put("file_id", ref.fileId().toString());
        } else {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("job_id", ref.jobOutput().jobId().toString());
            output.put("index", ref.jobOutput().index());
            stored.put("job_output", output);
        }
        return stored;
    }

    /** The row's inputs, by slot name, as a single run's {@code recipe.inputs} has them. */
    static Map<String, InputRef> fromStored(Map<String, Object> stored) {
        Map<String, InputRef> refs = new LinkedHashMap<>();
        if (stored == null) return refs;
        stored.forEach((slot, value) -> refs.put(slot, ref(value)));
        return refs;
    }

    private static InputRef ref(Object value) {
        if (!(value instanceof Map<?, ?> fields)) throw new IllegalStateException("a stored input is an object");
        if (fields.get("file_id") instanceof String fileId) return InputRef.file(UUID.fromString(fileId));
        if (fields.get("job_output") instanceof Map<?, ?> output
                && output.get("job_id") instanceof String jobId && output.get("index") instanceof Number index) {
            return InputRef.jobOutput(UUID.fromString(jobId), index.intValue());
        }
        throw new IllegalStateException("a stored input names a file or a job output");
    }
}
