package ai.godance.service.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ai.godance.config.PricingProperties;
import ai.godance.domain.Modality;
import ai.godance.domain.PriceField;
import ai.godance.domain.Provider;

/** Each anomaly category becomes a distinct {@link SanityGuard.Result}. */
class SanityGuardTest {

    private SanityGuard guard;

    @BeforeEach
    void setUp() {
        PricingProperties props = new PricingProperties();
        props.setMinMicrocents(1);
        props.setMaxMultiplier(10.0);
        guard = new SanityGuard(props);
    }

    @Test
    void check_okValue_returnsOk() {
        assertThat(guard.check(obs(5_500L), 5_000L)).isEqualTo(SanityGuard.Result.OK);
    }

    @Test
    void check_zero_returnsNonPositive() {
        assertThat(guard.check(obs(0L), 5_000L)).isEqualTo(SanityGuard.Result.NON_POSITIVE);
    }

    @Test
    void check_negative_returnsNonPositive() {
        assertThat(guard.check(obs(-1L), 5_000L)).isEqualTo(SanityGuard.Result.NON_POSITIVE);
    }

    @Test
    void check_above10x_returnsAboveCeiling() {
        assertThat(guard.check(obs(500_000L), 5_000L)).isEqualTo(SanityGuard.Result.ABOVE_CEILING);
    }

    @Test
    void check_nanSentinel_returnsNonFinite() {
        assertThat(guard.check(obs(Long.MIN_VALUE), 5_000L)).isEqualTo(SanityGuard.Result.NON_FINITE);
    }

    @Test
    void check_noPriorValue_doesNotEnforceCeiling() {
        // First observation of a brand-new model: no prior, but still positive — OK.
        assertThat(guard.check(obs(999_999L), null)).isEqualTo(SanityGuard.Result.OK);
    }

    private static ObservedPrice obs(long microcents) {
        return new ObservedPrice("anthropic/claude-opus-4-7", "claude-opus-4-7",
                Provider.ANTHROPIC, Modality.TEXT, PriceField.INPUT,
                microcents, BigDecimal.ZERO, "openrouter", null, Instant.now());
    }
}
