package ai.godance.web.dto;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import ai.godance.domain.InputFile;

/** Input files and the ways a request points at an input (round 11 brief 01). */
public final class InputDtos {

    private InputDtos() { }

    /**
     * How a request points at an input. Exactly one of the two:
     * <pre>
     * { "file_id": "…" }
     * { "job_output": { "job_id": "…", "index": 0 } }
     * </pre>
     * A {@code file_id} is one of the caller's uploaded files. A
     * {@code job_output} is an image output of one of the caller's own
     * finished jobs whose files are still kept.
     */
    public record InputRef(
            @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("file_id") UUID fileId,
            @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("job_output") JobOutputRef jobOutput
    ) {
        public static InputRef file(UUID fileId) {
            return new InputRef(fileId, null);
        }

        public static InputRef jobOutput(UUID jobId, int index) {
            return new InputRef(null, new JobOutputRef(jobId, index));
        }
    }

    /** One output of a job, by the job's id and the output's index. */
    public record JobOutputRef(
            @JsonProperty("job_id") UUID jobId,
            Integer index
    ) { }

    /** An uploaded file as the API returns it. Times are seconds since the epoch, as OpenAI's file object has them. */
    public record InputFileView(
            UUID id,
            String object,
            String purpose,
            long bytes,
            @JsonProperty("content_type") String contentType,
            int width,
            int height,
            @JsonInclude(JsonInclude.Include.ALWAYS) @JsonProperty("contains_person") Boolean containsPerson,
            @JsonProperty("created_at") long createdAt,
            @JsonInclude(JsonInclude.Include.ALWAYS) @JsonProperty("expires_at") Long expiresAt
    ) {
        public static final String PURPOSE = "generation_input";

        public static InputFileView from(InputFile f) {
            return new InputFileView(f.getId(), "file", PURPOSE, f.getBytes(), f.getContentType(),
                    f.getWidth(), f.getHeight(), f.getContainsPerson(),
                    f.getCreatedAt().getEpochSecond(),
                    f.getExpiresAt() == null ? null : f.getExpiresAt().getEpochSecond());
        }
    }

    /**
     * One input of a job, in {@code JobView.inputs}. {@code role} is
     * {@code image}, {@code mask}, {@code first_frame}, {@code last_frame}
     * or {@code reference_image}. Exactly one of {@code file_id} and
     * {@code job_output} says what it was. {@code url} is signed when the
     * job is read, and null once the file is gone.
     */
    public record JobInputView(
            String role,
            @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("file_id") UUID fileId,
            @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("job_output") JobOutputRef jobOutput,
            @JsonInclude(JsonInclude.Include.ALWAYS) String url
    ) { }

    /**
     * {@code inputs} on {@code GET /v1/auth/me}: what the app needs to know
     * to show, or hide, everything about inputs. {@code accepted} is true
     * only for the current {@code terms_version}.
     */
    public record InputsStatus(
            boolean enabled,
            @JsonProperty("terms_version") int termsVersion,
            boolean accepted,
            @JsonInclude(JsonInclude.Include.ALWAYS) @JsonProperty("accepted_at") Instant acceptedAt,
            @JsonProperty("allow_people") boolean allowPeople
    ) { }

    /** {@code POST /v1/inputs/terms}: the version of the input terms the user read and accepts. */
    public record AcceptTermsRequest(Integer version) { }
}
