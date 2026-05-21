package ai.godance.gateway.providers;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
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

import ai.godance.config.RunwayProperties;
import ai.godance.gateway.VideoDtos.GeneratedVideo;
import ai.godance.gateway.VideoDtos.VideoRequest;

class RunwayVideoProviderTest {

    private WireMockServer wm;
    private RunwayVideoProvider provider;

    @BeforeEach
    void start() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        provider = new RunwayVideoProvider(new RunwayProperties(
                "runway-test", wm.baseUrl(),
                Duration.ofMillis(50), Duration.ofSeconds(10)));
    }

    @AfterEach
    void stop() {
        wm.stop();
    }

    @Test
    void generate_submitPollDownload_returnsMp4Bytes() {
        byte[] videoBytes = new byte[] { 'M', 'P', '4', 'R', 9, 8, 7 };

        wm.stubFor(post(urlEqualTo("/v1/text_to_video"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"id\":\"rt_1\",\"status\":\"PENDING\"}")));

        wm.stubFor(get(urlPathMatching("/v1/tasks/rt_1"))
                .inScenario("runway-poll").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"id\":\"rt_1\",\"status\":\"RUNNING\"}"))
                .willSetStateTo("running"));
        wm.stubFor(get(urlPathMatching("/v1/tasks/rt_1"))
                .inScenario("runway-poll").whenScenarioStateIs("running")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"id\":\"rt_1\",\"status\":\"SUCCEEDED\",\"output\":[\""
                                + wm.baseUrl() + "/_test/r.mp4\"]}")));

        wm.stubFor(get(urlEqualTo("/_test/r.mp4"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "video/mp4")
                        .withBody(videoBytes)));

        VideoRequest req = new VideoRequest("runway-gen4", "A tiger surfs", "1280x720", 5, 1);
        List<GeneratedVideo> out = provider.generate(req).block(Duration.ofSeconds(5));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).bytes()).isEqualTo(videoBytes);
        assertThat(out.get(0).mimeType()).isEqualTo("video/mp4");
    }

    @Test
    void generate_taskFailed_propagatesError() {
        wm.stubFor(post(urlEqualTo("/v1/text_to_video"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"id\":\"rt_bad\",\"status\":\"PENDING\"}")));
        wm.stubFor(get(urlPathMatching("/v1/tasks/rt_bad"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"id\":\"rt_bad\",\"status\":\"FAILED\"}")));

        VideoRequest req = new VideoRequest("runway-gen4", "x", "1280x720", 5, 1);
        assertThatThrownBy(() -> provider.generate(req).block(Duration.ofSeconds(5)))
                .hasMessageContaining("FAILED");
    }
}
