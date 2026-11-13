package ai.godance.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PromoPropertiesTest {

    @Test
    void signupCreditEnabled_givenZeroAmount_expectsOff() {
        assertThat(new PromoProperties(0, 200).signupCreditEnabled()).isFalse();
    }

    @Test
    void signupCreditEnabled_givenPositiveAmount_expectsOn() {
        assertThat(new PromoProperties(100, 200).signupCreditEnabled()).isTrue();
    }

    @Test
    void constructor_givenNegativeAmount_expectsRejected() {
        assertThatThrownBy(() -> new PromoProperties(-1, 200))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("signup-credit-cents");
    }

    @Test
    void constructor_givenNegativeDailyLimit_expectsRejected() {
        assertThatThrownBy(() -> new PromoProperties(100, -1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("signup-credit-daily-limit");
    }
}
