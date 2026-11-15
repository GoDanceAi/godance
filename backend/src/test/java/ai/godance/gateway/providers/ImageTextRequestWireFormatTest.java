package ai.godance.gateway.providers;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import ai.godance.config.BflProperties;
import ai.godance.config.OpenAIProperties;
import ai.godance.config.VolcengineProperties;
import ai.godance.gateway.ImageDtos.ImageRequest;
import ai.godance.gateway.ImageProviderAdapter;

/**
 * An image request from text alone, byte for byte as each adapter sends it.
 * The three adapters here learned to send input images in round 11; a
 * request without one must leave them exactly as it did before. This test
 * passes unchanged on the commit before the change.
 */
class ImageTextRequestWireFormatTest {

    private WireMockServer wm;

    @BeforeEach
    void start() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        // The submit is all that matters here: whatever comes back, the request has been made.
        wm.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(500)));
    }

    @AfterEach
    void stop() {
        wm.stop();
    }

    private String sent(ImageProviderAdapter adapter, String path, ImageRequest request) {
        wm.resetRequests();
        try {
            adapter.generate(request).block(Duration.ofSeconds(5));
        } catch (RuntimeException expected) {
            // the stub answers 500
        }
        List<LoggedRequest> posts = wm.findAll(postRequestedFor(urlEqualTo(path)));
        assertThat(posts).hasSize(1);
        assertThat(wm.findAll(postRequestedFor(anyUrl()))).hasSize(1);
        assertThat(posts.get(0).getHeader("Content-Type")).startsWith("application/json");
        return posts.get(0).getBodyAsString();
    }

    @Test
    void generate_givenTextOnlyRequestOnOpenAi_expectsTheSameBytesAsBefore() {
        OpenAIImageProvider openAi = new OpenAIImageProvider(new OpenAIProperties("sk-test", wm.baseUrl(), Duration.ofSeconds(5)));

        assertThat(sent(openAi, "/v1/images/generations", new ImageRequest("gpt-image-2", "A lighthouse", "1024x1024", 2, null, "high")))
                .isEqualTo("{\"model\":\"gpt-image-2\",\"prompt\":\"A lighthouse\",\"n\":2,\"size\":\"1024x1024\",\"quality\":\"high\"}");
        assertThat(sent(openAi, "/v1/images/generations", new ImageRequest("gpt-image-1.5", "She said \"go\"\n然后出发", null, null, "vivid", null)))
                .isEqualTo("{\"model\":\"gpt-image-1.5\",\"prompt\":\"She said \\\"go\\\"\\n然后出发\",\"n\":1}");
    }

    @Test
    void generate_givenTextOnlyRequestOnSeedream_expectsTheSameBytesAsBefore() {
        VolcengineArkSeedreamProvider ark = new VolcengineArkSeedreamProvider(new VolcengineProperties(
                "volc-test", wm.baseUrl(), Duration.ofMillis(50), Duration.ofSeconds(5)));

        assertThat(sent(ark, "/api/v3/images/generations", new ImageRequest("seedream-5.0", "A koi pond", "2048x2048", 2, null, null)))
                .isEqualTo("{\"model\":\"seedream-5.0\",\"prompt\":\"A koi pond\",\"size\":\"2048x2048\",\"n\":2,\"response_format\":\"b64_json\"}");
        assertThat(sent(ark, "/api/v3/images/generations", new ImageRequest("seedream-5.0", "She said \"go\"\n然后出发", null, null, null, null)))
                .isEqualTo("{\"model\":\"seedream-5.0\",\"prompt\":\"She said \\\"go\\\"\\n然后出发\",\"size\":\"1024x1024\",\"n\":1,\"response_format\":\"b64_json\"}");
    }

    @Test
    void generate_givenTextOnlyRequestOnFlux_expectsTheSameBytesAsBefore() {
        BflProvider bfl = new BflProvider(new BflProperties("bfl-test", wm.baseUrl(), Duration.ofMillis(50), Duration.ofSeconds(5)));

        assertThat(sent(bfl, "/v1/flux-2-pro", new ImageRequest("flux-2-pro", "A lighthouse", "1024x768", 1, null, null)))
                .isEqualTo("{\"prompt\":\"A lighthouse\",\"width\":1024,\"height\":768}");
        assertThat(sent(bfl, "/v1/flux-pro-1.1", new ImageRequest("flux-1.1-pro", "She said \"go\"\n然后出发", null, null, null, null)))
                .isEqualTo("{\"prompt\":\"She said \\\"go\\\"\\n然后出发\",\"width\":1024,\"height\":1024}");
    }
}
