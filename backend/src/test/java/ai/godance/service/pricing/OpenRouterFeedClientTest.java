package ai.godance.service.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import ai.godance.config.PricingProperties;
import ai.godance.domain.PriceField;
import ai.godance.domain.Provider;

/**
 * Parses the trimmed-real-shape OpenRouter fixture and asserts the
 * USD-string → microcents/1k conversion is exact. Six fixture entries
 * deliberately exercise: catalog match, within-noise delta, above-noise
 * delta, NEW_VERSION-flavoured id, NEW_MODEL-flavoured id, and a row
 * carrying {@code pricing.image} the text parser must ignore.
 */
class OpenRouterFeedClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path FIXTURE = Path.of("src/test/resources/pricing-feed-fixtures/openrouter-models.json");

    private OpenRouterFeedClient client;

    @BeforeEach
    void setUp() {
        client = new OpenRouterFeedClient(WebClient.builder(), new PricingProperties(), JSON);
    }

    @Test
    void parse_givenRealShapeFixture_yieldsExactMicrocents() throws IOException {
        List<ObservedPrice> obs = client.parse(Files.readString(FIXTURE));

        // Six entries × two priced fields (prompt + completion) = 12.
        assertThat(obs).hasSize(12);
        ObservedPrice opusInput = byNameAndField(obs, "claude-opus-4-7", PriceField.INPUT);
        // USD per token 0.000005 → per 1k 0.005 → microcents per 1k = 5 000.
        assertThat(opusInput.microcents()).isEqualTo(5_000L);
        assertThat(opusInput.provider()).isEqualTo(Provider.ANTHROPIC);
        assertThat(opusInput.source()).isEqualTo("openrouter");
    }

    @Test
    void parse_withinNoise_emitsExactConversion_notRounded() throws IOException {
        List<ObservedPrice> obs = client.parse(Files.readString(FIXTURE));
        // 0.00000504 USD/token → 5.04 USD/1M → 5_040 microcents/1k.
        assertThat(byNameAndField(obs, "gpt-5.5", PriceField.INPUT).microcents()).isEqualTo(5_040L);
    }

    @Test
    void parse_imagePricingField_isIgnoredForTextEntry() throws IOException {
        List<ObservedPrice> obs = client.parse(Files.readString(FIXTURE));
        // gemini-3.1-flash-lite has a "pricing.image" string; we only emit prompt+completion.
        List<ObservedPrice> gemini = obs.stream()
                .filter(o -> "gemini-3.1-flash-lite".equals(o.godanceModelName()))
                .toList();
        assertThat(gemini).hasSize(2);
        assertThat(gemini.stream().map(ObservedPrice::field))
                .containsExactlyInAnyOrder(PriceField.INPUT, PriceField.OUTPUT);
    }

    @Test
    void parse_inferredProvider_fromVendorPrefix() throws IOException {
        List<ObservedPrice> obs = client.parse(Files.readString(FIXTURE));
        assertThat(byNameAndField(obs, "grok-mythos-5-0", PriceField.INPUT).provider()).isEqualTo(Provider.XAI);
        assertThat(byNameAndField(obs, "deepseek-v4-pro", PriceField.INPUT).provider()).isEqualTo(Provider.DEEPSEEK);
    }

    @Test
    void parse_nanValue_emitsMinValueSentinelForSanityGuard() throws IOException {
        String body = Files.readString(Path.of(
                "src/test/resources/pricing-feed-fixtures/openrouter-anomalies.json"));
        List<ObservedPrice> obs = client.parse(body);
        ObservedPrice gemini = byNameAndField(obs, "gemini-3.1-flash-lite", PriceField.INPUT);
        assertThat(gemini.microcents()).isEqualTo(Long.MIN_VALUE);
    }

    private static ObservedPrice byNameAndField(List<ObservedPrice> obs, String name, PriceField f) {
        return obs.stream()
                .filter(o -> name.equals(o.godanceModelName()) && o.field() == f)
                .findFirst().orElseThrow(() -> new AssertionError("missing " + name + " " + f));
    }
}
