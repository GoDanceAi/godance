package ai.godance.gateway.providers;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import ai.godance.config.VolcengineProperties;
import ai.godance.gateway.ImageDtos.GeneratedImage;
import ai.godance.gateway.ImageDtos.ImageRequest;

class VolcengineArkSeedreamProviderTest {

    private WireMockServer wm;
    private VolcengineArkSeedreamProvider provider;

    @BeforeEach
    void start() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        provider = new VolcengineArkSeedreamProvider(new VolcengineProperties(
                "ark-test-key", wm.baseUrl(),
                Duration.ofSeconds(2), Duration.ofSeconds(10)));
    }

    @AfterEach
    void stop() {
        wm.stop();
    }

    @Test
    void generate_decodesB64Json_intoBytes_andRequestsB64Format() {
        byte[] imageBytes = { 'P', 'N', 'G', 1, 2, 3, 4, 5 };
        String b64 = Base64.getEncoder().encodeToString(imageBytes);

        wm.stubFor(post(urlEqualTo("/api/v3/images/generations"))
                .withHeader("Authorization", equalTo("Bearer ark-test-key"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"created\":1700000000,\"data\":[{\"b64_json\":\"" + b64 + "\"}]}")));

        ImageRequest req = new ImageRequest("seedream-5.0", "A koi pond",
                "1024x1024", 1, null, null);
        List<GeneratedImage> out = provider.generate(req).block(Duration.ofSeconds(5));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).bytes()).isEqualTo(imageBytes);
        assertThat(out.get(0).mimeType()).isEqualTo("image/png");

        wm.verify(postRequestedFor(urlEqualTo("/api/v3/images/generations"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("seedream-5.0")))
                .withRequestBody(matchingJsonPath("$.prompt", equalTo("A koi pond")))
                .withRequestBody(matchingJsonPath("$.response_format", equalTo("b64_json"))));
    }
}
