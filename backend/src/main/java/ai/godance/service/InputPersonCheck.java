package ai.godance.service;

import java.util.UUID;

/**
 * Says whether an uploaded image shows a person (round 11 brief 01).
 *
 * <p>This is the interface only. Brief 02 implements it with the vision
 * chat model named by {@code godance.inputs.check-model}. While nothing
 * implements it, {@link InputService} treats every image as
 * {@link Verdict#UNAVAILABLE}, which refuses the upload unless people are
 * allowed: the check fails closed.
 *
 * <p>An implementation must answer {@link Verdict#UNAVAILABLE} for anything
 * but a clear yes or no: a timeout, an error, an answer it cannot read. It
 * must never log the image or put it in an audit entry, and the only place
 * it may send it is the check model.
 */
public interface InputPersonCheck {

    /** What the check said. */
    enum Verdict {
        /** A person, or part of one, is visible. */
        PERSON,
        /** No person is visible. */
        NO_PERSON,
        /** The check could not say. Treated as a refusal. */
        UNAVAILABLE
    }

    /**
     * Checks one image. Blocking; called from a bounded-elastic worker.
     *
     * @param userId      the uploader, for the platform's own usage record;
     *                    the user is never charged for the check
     * @param image       the image, already stripped of its metadata
     * @param contentType {@code image/png}, {@code image/jpeg} or {@code image/webp}
     */
    Verdict check(UUID userId, byte[] image, String contentType);
}
