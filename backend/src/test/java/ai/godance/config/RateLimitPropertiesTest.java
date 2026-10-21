package ai.godance.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import ai.godance.config.RateLimitProperties.Limit;

class RateLimitPropertiesTest {

    private static final Limit OK = new Limit(10, Duration.ofMinutes(15));

    @Test
    void constructor_givenMissingLimit_expectsIllegalState() {
        assertThatThrownBy(() -> new RateLimitProperties(true, OK, null, OK, OK, OK, OK, OK, OK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("login-per-email");
    }

    @Test
    void constructor_givenMissingEmailSendLimit_expectsIllegalState() {
        assertThatThrownBy(() -> new RateLimitProperties(true, OK, OK, OK, OK, OK, OK, OK, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("resend-per-account");
    }

    @Test
    void constructor_givenZeroRequests_expectsIllegalState() {
        assertThatThrownBy(() -> new RateLimitProperties(true, OK, OK, new Limit(0, Duration.ofHours(1)), OK,
                OK, OK, OK, OK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("signup-per-ip.requests");
    }

    @Test
    void constructor_givenZeroWindow_expectsIllegalState() {
        assertThatThrownBy(() -> new RateLimitProperties(true, OK, OK, OK, new Limit(5, Duration.ZERO),
                OK, OK, OK, OK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("verify-per-ip.window");
    }
}
