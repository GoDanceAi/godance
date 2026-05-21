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

import ai.godance.config.AlibabaProperties;
import ai.godance.gateway.ImageDtos.GeneratedImage;
import ai.godance.gateway.ImageDtos.ImageRequest;

class AlibabaQwenImageProviderTest {

    private WireMockServer wm;
    private AlibabaQwenImageProvider provider;

    @BeforeEach
    void start() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        provider = new AlibabaQwenImageProvider(
                new AlibabaProperties("qwen-test-key", wm.baseUrl(), Duration.ofSeconds(10)),
                Duration.ofMillis(50));   // tight poll for fast tests
    }

    @AfterEach
    void stop() {
        wm.stop();
    }

    @Test
    void generate_submitsThenPollsUntilSucceeded_thenDownloadsBytes() {
        byte[] imageBytes = { 'Q', 'W', 'E', 'N', 9, 8, 7 };

        // 1. Submit (X-DashScope-Async: enable) → task_id + PENDING.
        wm.stubFor(post(urlEqualTo("/api/v1/services/aigc/text2image/image-synthesis"))
                .withHeader("Authorization", equalTo("Bearer qwen-test-key"))
                .withHeader("X-DashScope-Async", equalTo("enable"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"output\":{\"task_id\":\"task-1\",\"task_status\":\"PENDING\"}}")));

        // 2. First poll RUNNING, second poll SUCCEEDED with a result URL.
        wm.stubFor(get(urlPathEqualTo("/api/v1/tasks/task-1"))
                .inScenario("qwen-poll").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"output\":{\"task_id\":\"task-1\",\"task_status\":\"RUNNING\"}}"))
                .willSetStateTo("running"));
        wm.stubFor(get(urlPathEqualTo("/api/v1/tasks/task-1"))
                .inScenario("qwen-poll").whenScenarioStateIs("running")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"output\":{\"task_id\":\"task-1\",\"task_status\":\"SUCCEEDED\","
                                + "\"results\":[{\"url\":\"" + wm.baseUrl() + "/_test/qwen.png\"}]}}")));

        // 3. Result download.
        wm.stubFor(get(urlEqualTo("/_test/qwen.png"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "image/png")
                        .withBody(imageBytes)));

        ImageRequest req = new ImageRequest("qwen-image-2.0", "A bamboo forest",
                "1024x1024", 1, null, null);
        List<GeneratedImage> out = provider.generate(req).block(Duration.ofSeconds(10));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).bytes()).isEqualTo(imageBytes);
        assertThat(out.get(0).mimeType()).isEqualTo("image/png");
    }

    @Test
    void generate_taskStatusFailed_propagatesError_doesNotKeepPolling() {
        wm.stubFor(post(urlEqualTo("/api/v1/services/aigc/text2image/image-synthesis"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"output\":{\"task_id\":\"task-err\",\"task_status\":\"PENDING\"}}")));
        wm.stubFor(get(urlPathEqualTo("/api/v1/tasks/task-err"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"output\":{\"task_id\":\"task-err\",\"task_status\":\"FAILED\"}}")));

        ImageRequest req = new ImageRequest("qwen-image-2.0", "...",
                "1024x1024", 1, null, null);
        assertThatThrownBy(() -> provider.generate(req).block(Duration.ofSeconds(5)))
                .hasMessageContaining("FAILED");
    }
}
