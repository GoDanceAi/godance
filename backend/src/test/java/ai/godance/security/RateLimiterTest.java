package ai.godance.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import ai.godance.config.RateLimitProperties;
import ai.godance.config.RateLimitProperties.Limit;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Counting, 429 and fail-open behaviour of {@link RateLimiter} against a
 * mocked Redis template. {@code AuthRateLimitIntegrationTest} runs the
 * Lua script against a real Redis.
 */
class RateLimiterTest {

    private static final Limit FIVE_PER_MINUTE = new Limit(5, Duration.ofMinutes(1));
    private static final String IP = "203.0.113.7";

    ReactiveStringRedisTemplate redis;
    RateLimiter limiter;

    @BeforeEach
    void setUp() {
        redis = mock(ReactiveStringRedisTemplate.class);
        limiter = new RateLimiter(redis, props(true));
    }

    private static RateLimitProperties props(boolean enabled) {
        Limit unused = new Limit(1, Duration.ofMinutes(1));
        return new RateLimitProperties(enabled, unused, unused, unused, unused);
    }

    private void redisCounts(Flux<Long> reply) {
        when(redis.<Long>execute(any(), anyList(), anyList())).thenReturn(reply);
    }

    @Test
    void check_givenCountAtLimit_expectsAllowed() {
        redisCounts(Flux.just(5L));

        StepVerifier.create(limiter.check("login-ip", IP, FIVE_PER_MINUTE)).verifyComplete();
        verify(redis, never()).getExpire(anyString());
    }

    @Test
    void check_givenCountOverLimit_expects429WithRetryAfterFromKeyTtl() {
        redisCounts(Flux.just(6L));
        when(redis.getExpire(anyString())).thenReturn(Mono.just(Duration.ofMillis(41_200)));

        StepVerifier.create(limiter.check("login-ip", IP, FIVE_PER_MINUTE))
                .expectErrorSatisfies(e -> assertThat(e)
                        .isInstanceOfSatisfying(RateLimitExceededException.class, ex -> {
                            assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                            assertThat(ex.getRetryAfterSeconds()).isEqualTo(42);
                            assertThat(ex.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("42");
                        }))
                .verify();
    }

    @Test
    void check_givenCountOverLimitAndNoTtl_expectsRetryAfterOfFullWindow() {
        redisCounts(Flux.just(6L));
        when(redis.getExpire(anyString())).thenReturn(Mono.empty());

        StepVerifier.create(limiter.check("login-ip", IP, FIVE_PER_MINUTE))
                .expectErrorSatisfies(e -> assertThat(e)
                        .isInstanceOfSatisfying(RateLimitExceededException.class,
                                ex -> assertThat(ex.getRetryAfterSeconds()).isEqualTo(60)))
                .verify();
    }

    @Test
    void check_givenCountOverLimitAndTtlLookupFails_expectsStill429() {
        redisCounts(Flux.just(6L));
        when(redis.getExpire(anyString())).thenReturn(Mono.error(new RedisConnectionFailureException("down")));

        StepVerifier.create(limiter.check("login-ip", IP, FIVE_PER_MINUTE))
                .verifyError(RateLimitExceededException.class);
    }

    @Test
    void check_givenRedisError_expectsFailOpen() {
        redisCounts(Flux.error(new RedisConnectionFailureException("down")));

        StepVerifier.create(limiter.check("login-ip", IP, FIVE_PER_MINUTE)).verifyComplete();
    }

    @Test
    void check_givenRedisSlowerThanTimeout_expectsFailOpen() {
        redisCounts(Flux.never());

        StepVerifier.withVirtualTime(() -> limiter.check("login-ip", IP, FIVE_PER_MINUTE))
                .expectSubscription()
                .thenAwait(RateLimiter.REDIS_TIMEOUT)
                .verifyComplete();
    }

    @Test
    void check_givenLimitingDisabled_expectsNoRedisCall() {
        limiter = new RateLimiter(redis, props(false));

        StepVerifier.create(limiter.check("login-ip", IP, FIVE_PER_MINUTE)).verifyComplete();
        verifyNoInteractions(redis);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void check_givenNoSubject_expectsNoRedisCall(String subject) {
        StepVerifier.create(limiter.check("login-ip", subject, FIVE_PER_MINUTE)).verifyComplete();
        verifyNoInteractions(redis);
    }

    @Test
    void check_givenNoSubscription_expectsNothingCounted() {
        limiter.check("login-ip", IP, FIVE_PER_MINUTE);

        verifyNoInteractions(redis);
    }

    @Test
    @SuppressWarnings("unchecked")
    void check_givenEmailSubject_expectsHashedKeyAndWindowInMillis() throws Exception {
        redisCounts(Flux.just(1L));
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<Object>> args = ArgumentCaptor.forClass(List.class);

        StepVerifier.create(limiter.check("login-email", "alice@example.com", FIVE_PER_MINUTE)).verifyComplete();

        verify(redis).execute(any(), keys.capture(), args.capture());
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest("alice@example.com".getBytes(StandardCharsets.UTF_8)));
        assertThat(keys.getValue()).containsExactly("rl:login-email:" + sha256);
        assertThat(args.getValue()).containsExactly("60000");
    }
}
