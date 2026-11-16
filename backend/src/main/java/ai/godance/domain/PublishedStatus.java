package ai.godance.domain;

/** Where a published recipe is in moderation (V39). Only {@link #PUBLIC} reaches a public route. */
public enum PublishedStatus {
    /** Submitted by its author; waiting for a moderator. */
    PENDING,
    /** Approved by a moderator; on the public routes. */
    PUBLIC,
    /** Refused by a moderator, with a reason the author sees. Never public. */
    REJECTED,
    /** Taken down by a moderator, unpublished by its author, or gone with the account. Never public again. */
    REMOVED
}
