package ai.godance.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import ai.godance.config.RateLimitProperties;
import ai.godance.config.RateLimitProperties.Limit;
import reactor.core.publisher.Mono;

/**
 * Fixed-window rate limiter on Redis. Each (bucket, subject) pair is one
 * counter: the first hit in a window creates it with the window as its
 * TTL, and a hit that takes the count past the limit fails with
 * {@link RateLimitExceededException}. INCR and PEXPIRE run as one Lua
 * script, so a crash between them can never leave a counter without a
 * TTL, which would lock its subject out for good.
 *
 * <p>Subjects are SHA-256 hashed into the key, so Redis holds no raw
 * emails or IPs and the key length is bounded whatever a client sends.
 *
 * <p>Fails open: if Redis errors or does not answer within
 * {@link #REDIS_TIMEOUT}, the request is allowed and a warning logged.
 * A Redis outage must not take login down with it.
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    static final Duration REDIS_TIMEOUT = Duration.ofMillis(500);

    /** Count this hit; the first hit of a window starts its TTL. Returns the count. */
    private static final RedisScript<Long> HIT = RedisScript.of("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);

    private final ReactiveStringRedisTemplate redis;
    private final RateLimitProperties props;

    public RateLimiter(ReactiveStringRedisTemplate redis, RateLimitProperties props) {
        this.redis = redis;
        this.props = props;
    }

    /**
     * Counts one request by {@code subject} against {@code bucket}.
     * Completes empty when the request is within {@code limit}, errors with
     * {@link RateLimitExceededException} when it is not. Nothing is counted
     * until subscription. A null or blank subject is not limited.
     */
    public Mono<Void> check(String bucket, String subject, Limit limit) {
        return Mono.defer(() -> {
            if (!props.enabled() || subject == null || subject.isBlank()) {
                return Mono.empty();
            }
            String key = "rl:" + bucket + ":" + sha256(subject);
            return redis.execute(HIT, List.of(key), List.of(Long.toString(limit.window().toMillis())))
                    .next()
                    .timeout(REDIS_TIMEOUT)
                    .onErrorResume(e -> {
                        log.warn("rate limit {} not enforced, Redis unavailable: {}", bucket, e.toString());
                        return Mono.empty();
                    })
                    .filter(count -> count > limit.requests())
                    .flatMap(over -> retryAfter(key, limit.window()))
                    .flatMap(wait -> Mono.error(new RateLimitExceededException(wait)));
        });
    }

    /** Time left in the key's window; the full window if Redis can't say. */
    private Mono<Duration> retryAfter(String key, Duration window) {
        return redis.getExpire(key)
                .timeout(REDIS_TIMEOUT)
                .filter(Duration::isPositive)
                .onErrorResume(e -> Mono.empty())
                .defaultIfEmpty(window);
    }

    private static String sha256(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
