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

import ai.godance.config.GoogleProperties;
import ai.godance.gateway.VideoDtos.GeneratedVideo;
import ai.godance.gateway.VideoDtos.VideoRequest;

class GoogleVeoVideoProviderTest {

    private WireMockServer wm;
    private GoogleVeoVideoProvider provider;

    @BeforeEach
    void start() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        provider = new GoogleVeoVideoProvider(new GoogleProperties(
                "google-test", wm.baseUrl(), Duration.ofSeconds(30)));
    }

    @AfterEach
    void stop() {
        wm.stop();
    }

    @Test
    void generate_submitPollDownload_returnsMp4Bytes() {
        byte[] videoBytes = new byte[] { 'M', 'P', '4', 'V', 4, 5, 6 };

        wm.stubFor(post(urlPathEqualTo("/v1beta/models/veo-3.1:predictLongRunning"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"name\":\"models/veo-3.1/operations/op_1\"}")));

        wm.stubFor(get(urlPathEqualTo("/v1beta/models/veo-3.1/operations/op_1"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"name\":\"models/veo-3.1/operations/op_1\",\"done\":true,"
                                + "\"response\":{\"generateVideoResponse\":{\"generatedSamples\":"
                                + "[{\"video\":{\"uri\":\"" + wm.baseUrl() + "/_test/veo.mp4\"}}]}}}")));

        wm.stubFor(get(urlEqualTo("/_test/veo.mp4"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "video/mp4")
                        .withBody(videoBytes)));

        VideoRequest req = new VideoRequest("veo-3.1", "A tiger surfs", "1280x720", 5, 1);
        List<GeneratedVideo> out = provider.generate(req).block(Duration.ofSeconds(5));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).bytes()).isEqualTo(videoBytes);
        assertThat(out.get(0).mimeType()).isEqualTo("video/mp4");
    }

    @Test
    void generate_operationReturnedError_propagatesError() {
        wm.stubFor(post(urlPathEqualTo("/v1beta/models/veo-3.1:predictLongRunning"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"name\":\"models/veo-3.1/operations/op_bad\"}")));
        wm.stubFor(get(urlPathEqualTo("/v1beta/models/veo-3.1/operations/op_bad"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"name\":\"models/veo-3.1/operations/op_bad\",\"done\":true,"
                                + "\"error\":{\"code\":3,\"message\":\"prompt rejected\"}}")));

        VideoRequest req = new VideoRequest("veo-3.1", "x", "1280x720", 5, 1);
        assertThatThrownBy(() -> provider.generate(req).block(Duration.ofSeconds(5)))
                .hasMessageContaining("failed");
    }
}
