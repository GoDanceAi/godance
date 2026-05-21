package ai.godance.gateway.providers;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import ai.godance.config.VolcengineProperties;
import ai.godance.gateway.VideoDtos.GeneratedVideo;
import ai.godance.gateway.VideoDtos.VideoRequest;

class VolcengineArkVideoProviderTest {

    private WireMockServer wm;
    private VolcengineArkVideoProvider provider;

    @BeforeEach
    void start() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        provider = new VolcengineArkVideoProvider(new VolcengineProperties(
                "volc-test", wm.baseUrl(),
                Duration.ofMillis(50),
                Duration.ofSeconds(10)));
    }

    @AfterEach
    void stop() {
        wm.stop();
    }

    @Test
    void generate_submitPollDownload_returnsMp4Bytes() {
        byte[] videoBytes = new byte[] { 'M', 'P', '4', 0, 0, 0, 28, 'f', 't', 'y', 'p' };

        wm.stubFor(post(urlEqualTo("/api/v3/contents/generations/tasks"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"id\":\"task_xyz\"}")));

        wm.stubFor(get(urlPathMatching("/api/v3/contents/generations/tasks/task_xyz"))
                .inScenario("ark-poll").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"id\":\"task_xyz\",\"status\":\"running\"}"))
                .willSetStateTo("running"));
        wm.stubFor(get(urlPathMatching("/api/v3/contents/generations/tasks/task_xyz"))
                .inScenario("ark-poll").whenScenarioStateIs("running")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"id\":\"task_xyz\",\"status\":\"succeeded\","
                                + "\"content\":{\"video_url\":\"" + wm.baseUrl() + "/_test/out.mp4\"}}")));

        wm.stubFor(get(urlEqualTo("/_test/out.mp4"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "video/mp4")
                        .withBody(videoBytes)));

        VideoRequest req = new VideoRequest("seedance-2.0", "A tiger surfs", "1280x720", 5, 1);
        List<GeneratedVideo> out = provider.generate(req).block(Duration.ofSeconds(5));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).bytes()).isEqualTo(videoBytes);
        assertThat(out.get(0).mimeType()).isEqualTo("video/mp4");
    }

    @Test
    void generate_taskFailed_propagatesError() {
        wm.stubFor(post(urlEqualTo("/api/v3/contents/generations/tasks"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"id\":\"task_bad\"}")));
        wm.stubFor(get(urlPathMatching("/api/v3/contents/generations/tasks/task_bad"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"id\":\"task_bad\",\"status\":\"failed\"}")));

        VideoRequest req = new VideoRequest("seedance-2.0", "x", "1280x720", 5, 1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                provider.generate(req).block(Duration.ofSeconds(5)))
                .hasMessageContaining("failed");
    }
}
