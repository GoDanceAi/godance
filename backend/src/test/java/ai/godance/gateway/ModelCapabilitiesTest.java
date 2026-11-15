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

    // ===== inputs (round 11) ============================================

    @Test
    void fromJson_givenNoInputs_expectsNoneAndNothingPublished() {
        ModelCapabilities c = ModelCapabilities.fromJson(Map.of("max_n", 4));

        assertThat(c.inputs()).isEqualTo(ModelCapabilities.Inputs.NONE);
        assertThat(c.inputs().images()).isFalse();
        assertThat(c.inputs().mask()).isFalse();
        assertThat(c.inputs().firstFrame()).isFalse();
        assertThat(c.inputs().lastFrame()).isFalse();
        assertThat(c.inputs().referenceImages()).isFalse();
        assertThat(c.toJson()).doesNotContainKey("inputs");
        assertThat(ModelCapabilities.UNKNOWN.inputs()).isEqualTo(ModelCapabilities.Inputs.NONE);
    }

    @Test
    void fromJson_givenImageInputs_expectsTheMostAndTheMaskAndTheSameJsonBack() {
        Map<String, Object> raw = Map.of("inputs", Map.of("images", Map.of("max", 4, "mask", true)));

        ModelCapabilities c = ModelCapabilities.fromJson(raw);

        assertThat(c.inputs().images()).isTrue();
        assertThat(c.inputs().maxImages()).isEqualTo(4);
        assertThat(c.inputs().mask()).isTrue();
        assertThat(c.isEmpty()).isFalse();
        assertThat(c.toJson()).isEqualTo(raw);
        // Without a mask, as a chat model states it.
        Map<String, Object> chat = Map.of("inputs", Map.of("images", Map.of("max", 10)));
        assertThat(ModelCapabilities.fromJson(chat).inputs().mask()).isFalse();
        assertThat(ModelCapabilities.fromJson(chat).toJson()).isEqualTo(chat);
    }

    @Test
    void fromJson_givenVideoInputs_expectsEachOneAndTheSameJsonBack() {
        Map<String, Object> raw = Map.of("default_seconds", 5, "inputs",
                Map.of("first_frame", true, "last_frame", false, "reference_images", Map.of("max", 4)));

        ModelCapabilities c = ModelCapabilities.fromJson(raw);

        assertThat(c.inputs().firstFrame()).isTrue();
        assertThat(c.inputs().lastFrame()).isFalse();
        assertThat(c.inputs().maxReferenceImages()).isEqualTo(4);
        assertThat(c.inputs().images()).isFalse();
        assertThat(c.toJson()).isEqualTo(raw);
    }

    @Test
    void fromJson_givenMalformedInputs_expectsNoneNeverLooser() {
        for (Object malformed : new Object[] {
                "images",                                              // not an object
                List.of("images"),
                Map.of("images", true),                                // images is not an object
                Map.of("images", Map.of("max", "4")),                  // a string, not a number
                Map.of("images", Map.of("max", 0)),
                Map.of("images", Map.of("mask", true)),                // a mask with no images
                Map.of("first_frame", "true"),                         // a string, not a boolean
                Map.of("first_frame", 1),
                Map.of("reference_images", 4),                         // not an object
                Map.of("reference_images", Map.of("max", -1))}) {
            ModelCapabilities c = ModelCapabilities.fromJson(Map.of("inputs", malformed));

            assertThat(c.inputs()).as(String.valueOf(malformed)).isEqualTo(ModelCapabilities.Inputs.NONE);
            assertThat(c.toJson()).doesNotContainKey("inputs");
        }
    }
}
