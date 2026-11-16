package ai.godance.routing;

import java.util.regex.Pattern;

/**
 * What a failed image or video job's stored error says about why it failed,
 * for batch fallbacks (round 14). A job keeps only its error text, written by
 * the generation services, and a provider's content refusal often arrives as
 * a plain 400 (OpenAI's {@code content_policy_violation}) or a terminal
 * status without a reason. So only failures that cannot be a refusal are
 * named; everything else is {@link #OTHER} and is never retried on another
 * model.
 */
public enum JobFailure {

    /**
     * The provider was down or overloaded (a 5xx or a 429), or the region's
     * allowlist refused it at dispatch. Never a judgement on the content.
     */
    PROVIDER_ERROR,
    /** The job ran out of time, or its worker was lost. */
    TIMEOUT,
    /** Anything else, a content refusal included: not retried. */
    OTHER;

    /** {@code upstream: 503 Service Unavailable from POST …}, {@code upstream: 429 Too Many Requests …}. */
    private static final Pattern SERVER_ERROR = Pattern.compile("^upstream: (5\\d\\d|429) ");

    /** The class of failure in a job's stored error message. */
    public static JobFailure of(String errorMessage) {
        if (errorMessage == null) return OTHER;
        String e = errorMessage.trim();
        if (e.startsWith("worker lost:")
                || e.contains("TimeoutException")
                || e.contains("Timeout on blocking read")
                || e.contains("Did not observe any item or terminal signal")) {
            return TIMEOUT;
        }
        if (e.startsWith("gateway dispatch:") || SERVER_ERROR.matcher(e).find()) {
            return PROVIDER_ERROR;
        }
        return OTHER;
    }
}
