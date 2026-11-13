package ai.godance.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ModelCapabilitiesTest {

    @Test
    void fromJson_givenEveryField_expectsThemAllAndTheSameJsonBack() {
        Map<String, Object> raw = Map.of(
                "sizes", List.of("1280x720", "720x1280"),
                "default_size", "1280x720",
                "durations", List.of(5, 10),
                "default_seconds", 5,
                "max_n", 1);

        ModelCapabilities c = ModelCapabilities.fromJson(raw);

        assertThat(c).isEqualTo(new ModelCapabilities(List.of("1280x720", "720x1280"), "1280x720",
                List.of(5, 10), 5, 1));
        assertThat(c.toJson()).isEqualTo(raw);
        assertThat(c.isEmpty()).isFalse();
    }

    @Test
    void fromJson_givenNullOrEmpty_expectsUnknownAndMaxNOfOne() {
        assertThat(ModelCapabilities.fromJson(null)).isEqualTo(ModelCapabilities.UNKNOWN);
        assertThat(ModelCapabilities.fromJson(Map.of())).isEqualTo(ModelCapabilities.UNKNOWN);
        assertThat(ModelCapabilities.UNKNOWN.isEmpty()).isTrue();
        assertThat(ModelCapabilities.UNKNOWN.maxNOrDefault()).isEqualTo(1);
        assertThat(ModelCapabilities.UNKNOWN.toJson()).isEmpty();
    }

    @Test
    void fromJson_givenMalformedValues_expectsThoseFieldsUnknownNeverLooser() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("max_n", "4");                 // a string, not a number
        raw.put("default_seconds", -5);
        raw.put("sizes", List.of("1024x1024", 7));
        raw.put("durations", "5,10");
        raw.put("default_size", 1024);

        ModelCapabilities c = ModelCapabilities.fromJson(raw);

        assertThat(c).isEqualTo(ModelCapabilities.UNKNOWN);
        assertThat(c.maxNOrDefault()).isEqualTo(1);
    }

    @Test
    void fromJson_givenWholeNumbersOfOtherTypes_expectsIntegers() {
        ModelCapabilities c = ModelCapabilities.fromJson(Map.of("max_n", 4L, "default_seconds", 6.0));

        assertThat(c.maxN()).isEqualTo(4);
        assertThat(c.maxNOrDefault()).isEqualTo(4);
        assertThat(c.defaultSeconds()).isEqualTo(6);
        assertThat(ModelCapabilities.fromJson(Map.of("max_n", 2.5)).maxN()).isNull();
    }

    @Test
    void metadataOf_givenBlankNames_expectsAbsent() {
        ModelMetadata m = ModelMetadata.of("  ", null, null);

        assertThat(m.displayName()).isNull();
        assertThat(m.description()).isNull();
        assertThat(m.capabilities()).isEqualTo(ModelCapabilities.UNKNOWN);
    }
}
