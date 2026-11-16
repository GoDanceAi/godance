package ai.godance.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Published recipes, profiles, follows and reports (round 14 brief 01), as
 * the round-14 contract names them. Field names are snake_case.
 *
 * <p>The public views ({@link PublicRecipeView}, {@link PublicProfileView})
 * carry exactly the fields the contract lists and nothing else: never an
 * account id, an email, a source job or an input file. A test checks their
 * keys.
 */
public final class CommunityDtos {

    private CommunityDtos() { }

    // ===== Requests =====================================================

    /** {@code PUT /v1/profile}. */
    public record ProfileRequest(
            String handle,
            @JsonProperty("display_name") String displayName,
            String link
    ) { }

    /** {@code POST /v1/recipes/{id}/publish}: the version, the licence, one to four example jobs, and optional tags. */
    public record PublishRequest(
            Integer version,
            String licence,
            @JsonProperty("job_ids") List<String> jobIds,
            List<String> tags
    ) { }

    /** {@code POST /v1/reports}. {@code id} is a published recipe's public id, or a profile's handle. */
    public record ReportRequest(String kind, String id, String reason) { }

    /** A moderator's reason: rejecting or removing. */
    public record ReasonRequest(String reason) { }

    // ===== The caller's own =============================================

    /**
     * {@code GET} and {@code PUT /v1/profile}: the caller's profile as they
     * set it, and whether they may publish. The profile fields are null
     * before one is set. {@code approved} says a moderator approved it, which
     * fixes the handle; {@code change_awaiting_review} says the display name
     * or link changed since, and the public routes still show the approved
     * ones. {@code review_reason} is why a moderator refused the last change.
     * {@code cannot_publish} is null when the caller may publish, otherwise
     * why not: {@code email_not_verified}, {@code not_adult} or
     * {@code no_profile}.
     */
    public record ProfileView(
            @JsonInclude(JsonInclude.Include.ALWAYS) String handle,
            @JsonInclude(JsonInclude.Include.ALWAYS) @JsonProperty("display_name") String displayName,
            @JsonInclude(JsonInclude.Include.ALWAYS) String link,
            boolean approved,
            @JsonProperty("change_awaiting_review") boolean changeAwaitingReview,
            @JsonInclude(JsonInclude.Include.ALWAYS) @JsonProperty("review_reason") String reviewReason,
            long followers,
            @JsonInclude(JsonInclude.Include.ALWAYS) @JsonProperty("cannot_publish") String cannotPublish,
            List<String> licences
    ) { }

    /**
     * A recipe's submission as its author sees it: {@code GET},
     * {@code POST /v1/recipes/{id}/publish}. {@code reason} is the
     * moderator's, for a rejected or removed submission; null when the
     * author unpublished it. {@code job_ids} are the author's own jobs.
     */
    public record PublishView(
            @JsonProperty("public_id") String publicId,
            String status,
            int version,
            String licence,
            List<String> tags,
            @JsonProperty("job_ids") List<UUID> jobIds,
            @JsonInclude(JsonInclude.Include.ALWAYS) String reason,
            @JsonProperty("submitted_at") Instant submittedAt,
            @JsonInclude(JsonInclude.Include.ALWAYS) @JsonProperty("reviewed_at") Instant reviewedAt,
            @JsonInclude(JsonInclude.Include.ALWAYS) @JsonProperty("published_at") Instant publishedAt
    ) { }

    // ===== Public =======================================================

    /** A template variable: its name and the value it has when none is given. */
    public record PublicVariable(String name, @JsonProperty("default") String defaultValue) { }

    /** An input slot: its name, its role and the hint. No file: the runner names one. */
    public record PublicInputSlot(String slot, String role, String hint) { }

    /** An example output: the public copy's URL and its type. */
    public record PublicExample(String url, @JsonProperty("content_type") String contentType) { }

    /** What a recipe was made from: a gallery recipe by slug, or a published recipe by public id. */
    public record MadeFrom(String kind, String id) { }

    /** A recipe's author: the handle, and how many follow them. */
    public record PublicAuthor(String handle, long followers) { }

    /**
     * {@code GET /v1/public/recipes} (a bare array, newest first) and
     * {@code GET /v1/public/recipes/{public_id}}: exactly these fields.
     * {@code runs} and {@code runners} count finished jobs that ran it, and
     * the accounts that ran them, its author's own excluded.
     */
    public record PublicRecipeView(
            @JsonProperty("public_id") String publicId,
            String title,
            String modality,
            String template,
            List<PublicVariable> variables,
            List<PublicInputSlot> inputs,
            String model,
            Map<String, Object> params,
            List<PublicExample> examples,
            @JsonInclude(JsonInclude.Include.ALWAYS) @JsonProperty("made_from") MadeFrom madeFrom,
            long runs,
            long runners,
            String licence,
            PublicAuthor author,
            List<String> tags,
            @JsonProperty("published_at") Instant publishedAt
    ) { }

    /**
     * {@code GET /v1/public/profiles/{handle}}: the profile as a moderator
     * approved it, how many follow it (never who), and its public recipes,
     * newest first.
     */
    public record PublicProfileView(
            String handle,
            @JsonInclude(JsonInclude.Include.ALWAYS) @JsonProperty("display_name") String displayName,
            @JsonInclude(JsonInclude.Include.ALWAYS) String link,
            long followers,
            List<PublicRecipeView> recipes
    ) { }

    // ===== Moderation (staff only) ======================================

    /** An example as a moderator sees it: a signed link to the output, and the job it came from. */
    public record ReviewExample(int position, String url, @JsonProperty("content_type") String contentType,
                                @JsonProperty("source_job_id") UUID sourceJobId,
                                @JsonProperty("copied") boolean copied) { }

    /** The author as a moderator sees them. */
    public record ReviewAuthor(
            @JsonProperty("user_id") UUID userId,
            String email,
            String handle,
            @JsonProperty("display_name") String displayName,
            String link,
            @JsonProperty("approved_display_name") String approvedDisplayName,
            @JsonProperty("approved_link") String approvedLink,
            @JsonProperty("approved_at") Instant approvedAt,
            long followers
    ) { }

    /** One of the author's other submissions: what became of it. */
    public record PriorDecision(
            @JsonProperty("public_id") String publicId,
            String title,
            String status,
            String reason,
            @JsonProperty("reviewed_at") Instant reviewedAt
    ) { }

    /** An open report, as a moderator sees it. */
    public record ReviewReport(
            UUID id,
            String kind,
            String target,
            String reason,
            @JsonProperty("reporter_id") UUID reporterId,
            @JsonProperty("created_at") Instant createdAt
    ) { }

    /** A published recipe as a moderator reviews it. */
    public record ReviewRecipe(
            @JsonProperty("public_id") String publicId,
            String status,
            String title,
            String modality,
            int version,
            String template,
            List<PublicVariable> variables,
            List<PublicInputSlot> inputs,
            String model,
            Map<String, Object> params,
            String licence,
            List<String> tags,
            @JsonProperty("made_from") MadeFrom madeFrom,
            List<ReviewExample> examples,
            String reason,
            @JsonProperty("submitted_at") Instant submittedAt,
            @JsonProperty("published_at") Instant publishedAt,
            @JsonProperty("reviewed_at") Instant reviewedAt
    ) { }

    /**
     * One item of the moderation queue. {@code why} is {@code pending} (a new
     * submission), {@code reported} (a public recipe with open reports) or
     * {@code profile} (a profile change or a reported profile); {@code recipe}
     * is null for a profile item.
     */
    public record QueueItem(
            String why,
            ReviewRecipe recipe,
            ReviewAuthor author,
            @JsonProperty("prior_decisions") List<PriorDecision> priorDecisions,
            List<ReviewReport> reports
    ) { }
}
