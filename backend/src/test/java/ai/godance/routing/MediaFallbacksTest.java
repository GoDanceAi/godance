package ai.godance.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import ai.godance.routing.MediaFallback.Alternative;
import ai.godance.routing.MediaFallback.Trigger;

/** Which alternative a failed batch row goes to, if any, for each kind of failure and health. */
class MediaFallbacksTest {

    private static final UUID USER = UUID.randomUUID();
    private static final String SERVER_ERROR = "upstream: 502 Bad Gateway from POST http://openai.test/v1/images";
    private static final String TIMED_OUT = "worker lost: the job outlived its lease";
    private static final String REFUSED = "upstream: 400 Bad Request from POST http://openai.test/v1/images";

    private final UserRoutingPolicyService policies = mock(UserRoutingPolicyService.class);

    /** MediaFallbacks with the primary's success rate fixed. */
    private MediaFallbacks withHealth(Double rate) {
        return new MediaFallbacks(policies, null) {
            @Override
            Double successRate(String model) {
                return rate;
            }
        };
    }

    private void fallbacks(Alternative... alternatives) {
        when(policies.fallbackFor(USER, "gpt-image-2"))
                .thenReturn(Optional.of(new MediaFallback("gpt-image-2", List.of(alternatives))));
    }

    @Test
    void alternativeFor_givenAProviderError_expectsTheFirstAlternativeThatTakesOne() {
        fallbacks(new Alternative("flux-2-pro", Set.of(Trigger.TIMEOUT), null),
                new Alternative("gpt-image-1.5", Set.of(Trigger.PROVIDER_ERROR), null));

        assertThat(withHealth(1.0).alternativeFor(USER, "gpt-image-2", SERVER_ERROR)).contains("gpt-image-1.5");
        assertThat(withHealth(1.0).alternativeFor(USER, "gpt-image-2", TIMED_OUT)).contains("flux-2-pro");
    }

    @Test
    void alternativeFor_givenARefusalOrAnUnknownFailure_expectsNone() {
        fallbacks(new Alternative("gpt-image-1.5",
                Set.of(Trigger.PROVIDER_ERROR, Trigger.TIMEOUT, Trigger.HEALTH_BELOW), 1.0));

        assertThat(withHealth(0.0).alternativeFor(USER, "gpt-image-2", REFUSED)).isEmpty();
        assertThat(withHealth(0.0).alternativeFor(USER, "gpt-image-2", "upstream returned no images")).isEmpty();
    }

    @Test
    void alternativeFor_givenHealthBelowTheThreshold_expectsTheHealthAlternative() {
        fallbacks(new Alternative("gpt-image-1.5", Set.of(Trigger.HEALTH_BELOW), 0.5));

        assertThat(withHealth(0.4).alternativeFor(USER, "gpt-image-2", SERVER_ERROR)).contains("gpt-image-1.5");
        assertThat(withHealth(0.5).alternativeFor(USER, "gpt-image-2", SERVER_ERROR)).isEmpty();
        // Too few recent jobs to say: health_below does not apply.
        assertThat(withHealth(null).alternativeFor(USER, "gpt-image-2", TIMED_OUT)).isEmpty();
    }

    @Test
    void alternativeFor_givenNoFallbacksForTheModel_expectsNone() {
        when(policies.fallbackFor(USER, "gpt-image-2")).thenReturn(Optional.empty());

        assertThat(withHealth(0.0).alternativeFor(USER, "gpt-image-2", SERVER_ERROR)).isEmpty();
    }
}
