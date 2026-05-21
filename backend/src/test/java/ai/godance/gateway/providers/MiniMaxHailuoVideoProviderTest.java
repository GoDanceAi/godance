package ai.godance.gateway.providers;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import ai.godance.config.MiniMaxProperties;
import ai.godance.gateway.VideoDtos.GeneratedVideo;
import ai.godance.gateway.VideoDtos.VideoRequest;

class MiniMaxHailuoVideoProviderTest {

    private WireMockServer wm;
    private MiniMaxHailuoVideoProvider provider;

    @BeforeEach
    void start() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        provider = new MiniMaxHailuoVideoProvider(new MiniMaxProperties(
                "minimax-test", wm.baseUrl(), Duration.ofSeconds(30)));
    }

    @AfterEach
    void stop() {
        wm.stop();
    }

    @Test
    void generate_submitPollRetrieveDownload_returnsMp4Bytes() {
        byte[] videoBytes = new byte[] { 'M', 'P', '4', 'M', 3, 1, 4 };

        wm.stubFor(post(urlEqualTo("/v1/video_generation"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"task_id\":\"mt_1\"}")));

        wm.stubFor(get(urlPathEqualTo("/v1/query/video_generation"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"task_id\":\"mt_1\",\"status\":\"Success\",\"file_id\":\"fl_1\"}")));

        wm.stubFor(get(urlPathEqualTo("/v1/files/retrieve"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"file\":{\"download_url\":\""
                                + wm.baseUrl() + "/_test/m.mp4\"}}")));

        wm.stubFor(get(urlEqualTo("/_test/m.mp4"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "video/mp4")
                        .withBody(videoBytes)));

        VideoRequest req = new VideoRequest("minimax-hailuo-2.3", "A tiger surfs", "1080p", 6, 1);
        List<GeneratedVideo> out = provider.generate(req).block(Duration.ofSeconds(5));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).bytes()).isEqualTo(videoBytes);
        assertThat(out.get(0).mimeType()).isEqualTo("video/mp4");
    }

    @Test
    void generate_taskFailed_propagatesError() {
        wm.stubFor(post(urlEqualTo("/v1/video_generation"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"task_id\":\"mt_bad\"}")));
        wm.stubFor(get(urlPathEqualTo("/v1/query/video_generation"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"task_id\":\"mt_bad\",\"status\":\"Fail\"}")));

        VideoRequest req = new VideoRequest("minimax-hailuo-2.3", "x", "1080p", 6, 1);
        assertThatThrownBy(() -> provider.generate(req).block(Duration.ofSeconds(5)))
                .hasMessageContaining("Fail");
    }
}
