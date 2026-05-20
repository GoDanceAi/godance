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

import ai.godance.config.OpenAIProperties;
import ai.godance.gateway.VideoDtos.GeneratedVideo;
import ai.godance.gateway.VideoDtos.VideoRequest;

class SoraVideoProviderTest {

    private WireMockServer wm;
    private SoraVideoProvider provider;

    @BeforeEach
    void start() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        provider = new SoraVideoProvider(new OpenAIProperties(
                "sk-test", wm.baseUrl(), Duration.ofSeconds(30)));
    }

    @AfterEach
    void stop() {
        wm.stop();
    }

    @Test
    void generate_submitPollContent_returnsMp4Bytes() {
        byte[] videoBytes = new byte[] { 'M', 'P', '4', 1, 2, 3 };

        wm.stubFor(post(urlEqualTo("/v1/videos"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"id\":\"vid_abc\",\"status\":\"queued\"}")));

        wm.stubFor(get(urlPathMatching("/v1/videos/vid_abc"))
                .inScenario("sora-poll").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"id\":\"vid_abc\",\"status\":\"in_progress\"}"))
                .willSetStateTo("in-progress"));
        wm.stubFor(get(urlPathMatching("/v1/videos/vid_abc"))
                .inScenario("sora-poll").whenScenarioStateIs("in-progress")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"id\":\"vid_abc\",\"status\":\"completed\"}")));

        wm.stubFor(get(urlEqualTo("/v1/videos/vid_abc/content"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "video/mp4")
                        .withBody(videoBytes)));

        VideoRequest req = new VideoRequest("sora-2", "A test clip", "1280x720", 5, 1);
        List<GeneratedVideo> out = provider.generate(req).block(Duration.ofMinutes(2));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).bytes()).isEqualTo(videoBytes);
        assertThat(out.get(0).mimeType()).isEqualTo("video/mp4");
    }
}
