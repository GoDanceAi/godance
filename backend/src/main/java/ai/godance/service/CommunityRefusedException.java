package ai.godance.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * A refused community request (round 14 brief 01): problem+json whose
 * {@code code} extension member says which kind, so a client can branch
 * without reading {@code detail}.
 */
public final class CommunityRefusedException extends ResponseStatusException {

    /** {@code godance.community.enabled} is false. 404. */
    public static final String CODE_DISABLED = "community_disabled";
    /** The account may not set a profile or publish: not an adult, or its email is not verified. 403. */
    public static final String CODE_NOT_ALLOWED = "community_not_allowed";
    /** A handle, display name or link the rules refuse. 400. */
    public static final String CODE_INVALID_PROFILE = "invalid_profile";
    /** The handle belongs to another account, or is reserved. 409. */
    public static final String CODE_HANDLE_TAKEN = "handle_taken";
    /** The handle cannot change: a moderator approved the profile. 409. */
    public static final String CODE_HANDLE_LOCKED = "handle_locked";
    /** Publishing needs a profile first. 409. */
    public static final String CODE_PROFILE_REQUIRED = "profile_required";
    /** A publish request the rules refuse: version, licence, tags or examples. 400. */
    public static final String CODE_INVALID_PUBLISH = "invalid_publish";
    /** An example job that is not the caller's finished, unpurged run of this recipe. 400. */
    public static final String CODE_INVALID_EXAMPLE = "invalid_example";
    /** An example job that used an input file: examples show no upload. 400. */
    public static final String CODE_EXAMPLE_HAS_INPUTS = "example_has_inputs";
    /** A version with input slots while people are not allowed in inputs. 400. */
    public static final String CODE_INPUTS_NOT_ALLOWED = "published_inputs_not_allowed";
    /** The recipe already has a submission that is pending or public. 409. */
    public static final String CODE_ALREADY_PUBLISHED = "already_published";
    /** The account's daily submissions, or its pending and public recipes, are at their limit. 429 or 409. */
    public static final String CODE_LIMIT_REACHED = "publish_limit_reached";
    /** The recipe has no submission that is pending or public. 404. */
    public static final String CODE_NOT_PUBLISHED = "not_published";
    /** No public recipe, or no public profile, by that name. 404. */
    public static final String CODE_NOT_FOUND = "not_found";
    /** An account cannot follow itself. 400. */
    public static final String CODE_FOLLOW_SELF = "follow_self";
    /** A report the rules refuse: kind, target or reason. 400. */
    public static final String CODE_INVALID_REPORT = "invalid_report";
    /** A moderation step the item's state does not allow. 409. */
    public static final String CODE_WRONG_STATE = "moderation_conflict";
    /** The account's reports in the last hour are at the limit. 429. */
    public static final String CODE_REPORT_LIMIT = "report_limit_reached";
    /** An example's output is gone: the source job was purged. 409. */
    public static final String CODE_EXAMPLE_UNAVAILABLE = "example_unavailable";

    private CommunityRefusedException(HttpStatus status, String detail, String code) {
        super(status, detail);
        getBody().setProperty("code", code);
    }

    static CommunityRefusedException of(HttpStatus status, String code, String detail) {
        return new CommunityRefusedException(status, detail, code);
    }

    public static CommunityRefusedException disabled() {
        return new CommunityRefusedException(HttpStatus.NOT_FOUND, "the community is not open", CODE_DISABLED);
    }

    public static CommunityRefusedException notFound(String what) {
        return new CommunityRefusedException(HttpStatus.NOT_FOUND, what + " not found", CODE_NOT_FOUND);
    }

    public String code() {
        Object code = getBody().getProperties() == null ? null : getBody().getProperties().get("code");
        return code == null ? null : code.toString();
    }
}
