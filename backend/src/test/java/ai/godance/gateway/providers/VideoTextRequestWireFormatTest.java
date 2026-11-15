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

import ai.godance.config.KlingProperties;
import ai.godance.config.MiniMaxProperties;
import ai.godance.config.VolcengineProperties;
import ai.godance.gateway.VideoDtos.VideoRequest;
import ai.godance.gateway.VideoProviderAdapter;

/**
 * A video request from text alone, byte for byte as each adapter sends it.
 * The three adapters here learned to send images in round 11; a request
 * without one must leave them exactly as it did before. The expected bodies
 * were taken from the adapters as they were, and this test passes unchanged
 * on the commit before the change.
 */
class VideoTextRequestWireFormatTest {

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

    /** What the adapter posts to {@code path} for this request, as the bytes on the wire. */
    private String sent(VideoProviderAdapter adapter, String path, VideoRequest request) {
        wm.resetRequests();
        try {
            adapter.generate(request).block(Duration.ofSeconds(5));
        } catch (RuntimeException expected) {
            // the stub answers 500
        }
        List<LoggedRequest> posts = wm.findAll(postRequestedFor(urlEqualTo(path)));
        assertThat(posts).hasSize(1);
        assertThat(wm.findAll(postRequestedFor(anyUrl()))).hasSize(1);
        return posts.get(0).getBodyAsString();
    }

    @Test
    void generate_givenTextOnlyRequestOnSeedance_expectsTheSameBytesAsBefore() {
        VolcengineArkVideoProvider ark = new VolcengineArkVideoProvider(new VolcengineProperties(
                "volc-test", wm.baseUrl(), Duration.ofMillis(50), Duration.ofSeconds(10)));
        String path = "/api/v3/contents/generations/tasks";

        assertThat(sent(ark, path, new VideoRequest("seedance-2.0", "A tiger surfs", "1280x720", 5, 1)))
                .isEqualTo("{\"model\":\"seedance-2.0\",\"content\":[{\"type\":\"text\",\"text\":\"A tiger surfs\"}],"
                        + "\"duration\":5,\"ratio\":\"1280x720\"}");
        // Nothing named: the adapter's own defaults, and a prompt with characters JSON escapes.
        assertThat(sent(ark, path, new VideoRequest("seedance-2.0", "She said \"go\"\n然后出发", null, null, null)))
                .isEqualTo("{\"model\":\"seedance-2.0\",\"content\":[{\"type\":\"text\",\"text\":\"She said \\\"go\\\"\\n然后出发\"}],"
                        + "\"duration\":5,\"ratio\":\"1280x720\"}");
        assertThat(sent(ark, path, new VideoRequest("seedance-2.0", "Tall", "720x1280", 10, 2)))
                .isEqualTo("{\"model\":\"seedance-2.0\",\"content\":[{\"type\":\"text\",\"text\":\"Tall\"}],"
                        + "\"duration\":10,\"ratio\":\"720x1280\"}");
    }

    @Test
    void generate_givenTextOnlyRequestOnKling_expectsTheSameBytesAsBefore() {
        KlingVideoProvider kling = new KlingVideoProvider(new KlingProperties(
                "kling-test", wm.baseUrl(), Duration.ofMillis(50), Duration.ofSeconds(10)));
        String path = "/v1/videos/text2video";

        assertThat(sent(kling, path, new VideoRequest("kling-3.0", "A tiger surfs", "1280x720", 5, 1)))
                .isEqualTo("{\"model_name\":\"kling-3.0\",\"prompt\":\"A tiger surfs\",\"duration\":5,\"aspect_ratio\":\"16:9\"}");
        assertThat(sent(kling, path, new VideoRequest("kling-3.0", "She said \"go\"\n然后出发", "9:16", null, null)))
                .isEqualTo("{\"model_name\":\"kling-3.0\",\"prompt\":\"She said \\\"go\\\"\\n然后出发\",\"duration\":5,\"aspect_ratio\":\"9:16\"}");
        assertThat(sent(kling, path, new VideoRequest("kling-3.0", "Long", null, 10, 2)))
                .isEqualTo("{\"model_name\":\"kling-3.0\",\"prompt\":\"Long\",\"duration\":10,\"aspect_ratio\":\"16:9\"}");
    }

    @Test
    void generate_givenTextOnlyRequestOnHailuo_expectsTheSameBytesAsBefore() {
        MiniMaxHailuoVideoProvider minimax = new MiniMaxHailuoVideoProvider(new MiniMaxProperties(
                "minimax-test", wm.baseUrl(), Duration.ofSeconds(30)));
        String path = "/v1/video_generation";

        assertThat(sent(minimax, path, new VideoRequest("minimax-hailuo-2.3", "A tiger surfs", "1280x720", 5, 1)))
                .isEqualTo("{\"model\":\"minimax-hailuo-2.3\",\"prompt\":\"A tiger surfs\",\"duration\":5}");
        assertThat(sent(minimax, path, new VideoRequest("minimax-hailuo-2.3", "She said \"go\"\n然后出发", null, 10, null)))
                .isEqualTo("{\"model\":\"minimax-hailuo-2.3\",\"prompt\":\"She said \\\"go\\\"\\n然后出发\",\"duration\":10}");
        // No length named: the default the adapter declares.
        assertThat(sent(minimax, path, new VideoRequest("minimax-hailuo-2.3", "Default", null, null, null)))
                .isEqualTo("{\"model\":\"minimax-hailuo-2.3\",\"prompt\":\"Default\",\"duration\":"
                        + minimax.defaultDurationSeconds() + "}");
    }
}
