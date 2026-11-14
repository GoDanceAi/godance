package ai.godance.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.util.unit.DataSize;

import ai.godance.domain.ImageResponseMode;

class ImagesPropertiesTest {

    private static ImagesProperties bind(Map<String, String> settings) {
        return new Binder(new MapConfigurationPropertySource(settings))
                .bindOrCreate("godance.images", ImagesProperties.class);
    }

    @Test
    void bind_givenNothingSet_expectsTheBriefsDefaults() {
        ImagesProperties p = bind(Map.of());

        // New keys behave as every key did before: nothing changes until the operator says so.
        assertThat(p.newKeyResponse()).isEqualTo(ImageResponseMode.ASYNC);
        assertThat(p.syncWait()).isEqualTo(Duration.ofSeconds(90));
        assertThat(p.syncPollInterval()).isEqualTo(Duration.ofSeconds(1));
        assertThat(p.maxInlineBytes()).isEqualTo(DataSize.ofMegabytes(25));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sync", "SYNC", "Sync"})
    void bind_givenSyncInAnyCase_expectsNewKeysToWait(String value) {
        assertThat(bind(Map.of("godance.images.new-key-response", value)).newKeyResponse())
                .isEqualTo(ImageResponseMode.SYNC);
    }

    @Test
    void bind_givenAWaitOfExactly300Seconds_expectsItAccepted() {
        assertThat(bind(Map.of("godance.images.sync-wait", "300s")).syncWait())
                .isEqualTo(Duration.ofSeconds(300));
    }

    @ParameterizedTest
    @ValueSource(strings = {"301s", "6m", "1h"})
    void bind_givenAWaitAbove300Seconds_expectsTheConfigurationRefused(String value) {
        assertThatThrownBy(() -> bind(Map.of("godance.images.sync-wait", value)))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause().hasMessageContaining("sync-wait must be at most 300s");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0s", "-5s"})
    void bind_givenAWaitThatIsNotPositive_expectsTheConfigurationRefused(String value) {
        assertThatThrownBy(() -> bind(Map.of("godance.images.sync-wait", value)))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause().hasMessageContaining("sync-wait must be positive");
    }

    @Test
    void bind_givenAPollIntervalOfZero_expectsTheConfigurationRefused() {
        assertThatThrownBy(() -> bind(Map.of("godance.images.sync-poll-interval", "0ms")))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause().hasMessageContaining("sync-poll-interval");
    }

    @Test
    void bind_givenAModeThatIsNeither_expectsTheConfigurationRefused() {
        assertThatThrownBy(() -> bind(Map.of("godance.images.new-key-response", "blocking")))
                .isInstanceOf(RuntimeException.class);
    }
}
