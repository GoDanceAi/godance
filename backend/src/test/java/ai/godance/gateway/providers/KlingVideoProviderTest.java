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

import ai.godance.config.KlingProperties;
import ai.godance.gateway.VideoDtos.GeneratedVideo;
import ai.godance.gateway.VideoDtos.VideoRequest;

class KlingVideoProviderTest {

    private WireMockServer wm;
    private KlingVideoProvider provider;

    @BeforeEach
    void start() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        provider = new KlingVideoProvider(new KlingProperties(
                "kling-test", wm.baseUrl(),
                Duration.ofMillis(50), Duration.ofSeconds(10)));
    }

    @AfterEach
    void stop() {
        wm.stop();
    }

    @Test
    void generate_submitPollDownload_returnsMp4Bytes() {
        byte[] videoBytes = new byte[] { 'M', 'P', '4', 'K', 1, 2, 3 };

        wm.stubFor(post(urlEqualTo("/v1/videos/text2video"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"code\":0,\"message\":\"SUCCEED\","
                                + "\"data\":{\"task_id\":\"kt_1\",\"task_status\":\"submitted\"}}")));

        wm.stubFor(get(urlPathMatching("/v1/videos/text2video/kt_1"))
                .inScenario("kling-poll").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"code\":0,\"data\":{\"task_id\":\"kt_1\","
                                + "\"task_status\":\"processing\"}}"))
                .willSetStateTo("processing"));
        wm.stubFor(get(urlPathMatching("/v1/videos/text2video/kt_1"))
                .inScenario("kling-poll").whenScenarioStateIs("processing")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"code\":0,\"data\":{\"task_id\":\"kt_1\","
                                + "\"task_status\":\"succeed\",\"task_result\":{\"videos\":[{\"url\":\""
                                + wm.baseUrl() + "/_test/k.mp4\"}]}}}")));

        wm.stubFor(get(urlEqualTo("/_test/k.mp4"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "video/mp4")
                        .withBody(videoBytes)));

        VideoRequest req = new VideoRequest("kling-3.0", "A tiger surfs", "16:9", 5, 1);
        List<GeneratedVideo> out = provider.generate(req).block(Duration.ofSeconds(5));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).bytes()).isEqualTo(videoBytes);
        assertThat(out.get(0).mimeType()).isEqualTo("video/mp4");
    }

    @Test
    void generate_taskFailed_propagatesError() {
        wm.stubFor(post(urlEqualTo("/v1/videos/text2video"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"code\":0,\"data\":{\"task_id\":\"kt_bad\","
                                + "\"task_status\":\"submitted\"}}")));
        wm.stubFor(get(urlPathMatching("/v1/videos/text2video/kt_bad"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"code\":0,\"data\":{\"task_id\":\"kt_bad\","
                                + "\"task_status\":\"failed\"}}")));

        VideoRequest req = new VideoRequest("kling-3.0", "x", "16:9", 5, 1);
        assertThatThrownBy(() -> provider.generate(req).block(Duration.ofSeconds(5)))
                .hasMessageContaining("failed");
    }
}
