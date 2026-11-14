package ai.godance.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class AttributionPropertiesTest {

    private static AttributionProperties bind(Map<String, String> settings) {
        return new Binder(new MapConfigurationPropertySource(settings))
                .bindOrCreate("godance.attribution", AttributionProperties.class);
    }

    @Test
    void bind_givenNothingSet_expectsTheBriefsDefaults() {
        AttributionProperties p = bind(Map.of());

        assertThat(p.firstTouchWindow()).isEqualTo(Duration.ofHours(168));
        assertThat(p.firstTouchLimitPerAccount()).isEqualTo(20);
    }

    @Test
    void bind_givenAWindowSet_expectsIt() {
        AttributionProperties p = bind(Map.of("godance.attribution.first-touch-window-hours", "24"));

        assertThat(p.firstTouchWindow()).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void new_givenANegativeWindow_expectsItRefused() {
        assertThatThrownBy(() -> new AttributionProperties(-1, 20))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("first-touch-window-hours");
    }

    @Test
    void new_givenALimitBelowOne_expectsItRefused() {
        assertThatThrownBy(() -> new AttributionProperties(168, 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("first-touch-limit-per-account");
    }
}
