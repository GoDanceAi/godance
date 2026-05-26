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
import ai.godance.domain.Modality;
import ai.godance.domain.PriceField;

class LiteLLMFeedClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path FIXTURE = Path.of("src/test/resources/pricing-feed-fixtures/litellm-prices.json");

    private LiteLLMFeedClient client;

    @BeforeEach
    void setUp() {
        client = new LiteLLMFeedClient(WebClient.builder(), new PricingProperties(), JSON);
    }

    @Test
    void parse_chatModes_emitTextMicrocents() throws IOException {
        List<ObservedPrice> obs = client.parse(Files.readString(FIXTURE));
        // claude-opus-4-7-20251220 → input 0.000005 USD/token → 5_000 microcents/1k.
        ObservedPrice opusIn = pick(obs, "claude-opus-4-7-20251220", PriceField.INPUT);
        assertThat(opusIn.microcents()).isEqualTo(5_000L);
        assertThat(opusIn.modality()).isEqualTo(Modality.TEXT);
        assertThat(opusIn.sourceUrl()).isEqualTo("https://docs.anthropic.com/claude/docs/models-overview");
    }

    @Test
    void parse_imageGeneration_emitsPerImageMicrocents_andStripsResolutionPrefix() throws IOException {
        List<ObservedPrice> obs = client.parse(Files.readString(FIXTURE));
        // "1024-x-1024/seedream-5.0" with output_cost_per_image=0.035 → 35_000 microcents.
        ObservedPrice seedream = pick(obs, "1024-x-1024/seedream-5.0", PriceField.PER_IMAGE);
        assertThat(seedream.microcents()).isEqualTo(35_000L);
        assertThat(seedream.modality()).isEqualTo(Modality.IMAGE);
        assertThat(seedream.godanceModelName()).isEqualTo("seedream-5.0");
    }

    @Test
    void parse_videoGeneration_emitsPerSecondMicrocents() throws IOException {
        List<ObservedPrice> obs = client.parse(Files.readString(FIXTURE));
        // veo-3.1-fast at 0.15/s → 150_000 microcents.
        ObservedPrice veo = pick(obs, "veo-3.1-fast", PriceField.PER_SECOND);
        assertThat(veo.microcents()).isEqualTo(150_000L);
        assertThat(veo.modality()).isEqualTo(Modality.VIDEO);
    }

    @Test
    void parse_embeddingMode_isSkipped() throws IOException {
        List<ObservedPrice> obs = client.parse(Files.readString(FIXTURE));
        assertThat(obs.stream().map(ObservedPrice::sourceModelId))
                .doesNotContain("text-embedding-3-small");
    }

    private static ObservedPrice pick(List<ObservedPrice> obs, String sourceId, PriceField f) {
        return obs.stream().filter(o -> sourceId.equals(o.sourceModelId()) && o.field() == f)
                .findFirst().orElseThrow(() -> new AssertionError("missing " + sourceId + " " + f));
    }
}
