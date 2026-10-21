package ai.godance.security;

import java.time.Duration;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 429 Too Many Requests from {@link RateLimiter}. Rendered as RFC 7807
 * problem details like any other {@link ResponseStatusException}, plus a
 * {@code Retry-After} header (whole seconds until the window resets).
 */
public class RateLimitExceededException extends ResponseStatusException {

    private final long retryAfterSeconds;

    public RateLimitExceededException(Duration retryAfter) {
        this(Math.max(1, (retryAfter.toMillis() + 999) / 1000));
    }

    private RateLimitExceededException(long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, "too many requests, try again in " + describe(retryAfterSeconds));
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    @Override
    public HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        return headers;
    }

    /** The SPA shows {@code detail} verbatim, so round long waits up to minutes. */
    private static String describe(long seconds) {
        if (seconds < 60) return seconds + (seconds == 1 ? " second" : " seconds");
        long minutes = (seconds + 59) / 60;
        return minutes + (minutes == 1 ? " minute" : " minutes");
    }
}
