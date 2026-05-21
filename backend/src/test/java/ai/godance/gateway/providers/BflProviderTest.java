package ai.godance.gateway.providers;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import ai.godance.config.BflProperties;
import ai.godance.gateway.ImageDtos.GeneratedImage;
import ai.godance.gateway.ImageDtos.ImageRequest;

class BflProviderTest {

    private WireMockServer wm;
    private BflProvider provider;

    @BeforeEach
    void start() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        provider = new BflProvider(new BflProperties(
                "bfl-test-key", wm.baseUrl(),
                Duration.ofMillis(50),      // tight poll for fast tests
                Duration.ofSeconds(5)));
    }

    @AfterEach
    void stop() {
        wm.stop();
    }

    @Test
    void generate_submitsThenPollsUntilReady_thenDownloadsBytes() {
        byte[] imageBytes = new byte[] { 'J', 'P', 'G', 1, 2, 3, 4, 5 };

        // 1. Submit: POST /v1/flux-pro-1.1 → returns id + polling_url
        wm.stubFor(post(urlEqualTo("/v1/flux-pro-1.1"))
                .withHeader("x-key", equalTo("bfl-test-key"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"id\":\"job-abc\",\"polling_url\":\"unused\"}")));

        // 2. First poll: Pending. Second poll: Ready with sample URL.
        wm.stubFor(get(urlPathEqualTo("/v1/get_result"))
                .inScenario("bfl-poll").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"id\":\"job-abc\",\"status\":\"Pending\"}"))
                .willSetStateTo("polled-once"));
        wm.stubFor(get(urlPathEqualTo("/v1/get_result"))
                .inScenario("bfl-poll").whenScenarioStateIs("polled-once")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody(("{\"id\":\"job-abc\",\"status\":\"Ready\","
                                + "\"result\":{\"sample\":\"" + wm.baseUrl() + "/_test/sample.jpg\"}}"))));

        // 3. Sample download
        wm.stubFor(get(urlEqualTo("/_test/sample.jpg"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "image/jpeg")
                        .withBody(imageBytes)));

        ImageRequest req = new ImageRequest("flux-1.1-pro", "A red boat",
                "1024x1024", 1, null, null);
        List<GeneratedImage> out = provider.generate(req).block(Duration.ofSeconds(10));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).bytes()).isEqualTo(imageBytes);
        assertThat(out.get(0).mimeType()).isEqualTo("image/jpeg");
    }

    @Test
    void generate_terminalErrorStatus_propagatesError_doesNotKeepPolling() {
        wm.stubFor(post(urlEqualTo("/v1/flux-pro-1.1"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"id\":\"job-err\"}")));
        wm.stubFor(get(urlPathEqualTo("/v1/get_result"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"id\":\"job-err\",\"status\":\"Content Moderated\"}")));

        ImageRequest req = new ImageRequest("flux-1.1-pro", "...",
                "1024x1024", 1, null, null);
        assertThatThrownBy(() -> provider.generate(req).block(Duration.ofSeconds(5)))
                .hasMessageContaining("Content Moderated");
    }

    @Test
    void generate_flux2Pro_submitsToFlux2ProEndpoint() {
        byte[] imageBytes = new byte[] { 'F', 'L', 'U', 'X', '2', 9 };

        wm.stubFor(post(urlEqualTo("/v1/flux-2-pro"))
                .withHeader("x-key", equalTo("bfl-test-key"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"id\":\"job-f2\",\"polling_url\":\"unused\"}")));
        wm.stubFor(get(urlPathEqualTo("/v1/get_result"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"id\":\"job-f2\",\"status\":\"Ready\","
                                + "\"result\":{\"sample\":\"" + wm.baseUrl() + "/_test/f2.jpg\"}}")));
        wm.stubFor(get(urlEqualTo("/_test/f2.jpg"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "image/jpeg")
                        .withBody(imageBytes)));

        ImageRequest req = new ImageRequest("flux-2-pro", "A glass sphere",
                "1024x1024", 1, null, null);
        List<GeneratedImage> out = provider.generate(req).block(Duration.ofSeconds(10));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).bytes()).isEqualTo(imageBytes);
    }
}
