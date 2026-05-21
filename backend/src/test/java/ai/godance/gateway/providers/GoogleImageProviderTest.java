package ai.godance.gateway.providers;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import ai.godance.config.GoogleProperties;
import ai.godance.gateway.ImageDtos.GeneratedImage;
import ai.godance.gateway.ImageDtos.ImageRequest;

class GoogleImageProviderTest {

    private WireMockServer wm;
    private GoogleImageProvider provider;

    @BeforeEach
    void start() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        provider = new GoogleImageProvider(
                new GoogleProperties("google-test-key", wm.baseUrl(), Duration.ofSeconds(10)));
    }

    @AfterEach
    void stop() {
        wm.stop();
    }

    @Test
    void generate_imagen_decodesPredictBytes() {
        byte[] imageBytes = { 'I', 'M', 'G', 'N', 4, 2 };
        String b64 = Base64.getEncoder().encodeToString(imageBytes);

        // imagen-4 routes to the :predict endpoint with the mapped model id.
        wm.stubFor(post(urlPathEqualTo("/v1beta/models/imagen-4.0-generate-001:predict"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"predictions\":[{\"bytesBase64Encoded\":\"" + b64
                                + "\",\"mimeType\":\"image/png\"}]}")));

        ImageRequest req = new ImageRequest("imagen-4", "A mountain lake",
                "1024x1024", 1, null, null);
        List<GeneratedImage> out = provider.generate(req).block(Duration.ofSeconds(5));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).bytes()).isEqualTo(imageBytes);
        assertThat(out.get(0).mimeType()).isEqualTo("image/png");

        wm.verify(postRequestedFor(urlPathEqualTo("/v1beta/models/imagen-4.0-generate-001:predict"))
                .withRequestBody(matchingJsonPath("$.instances[0].prompt", equalTo("A mountain lake"))));
    }

    @Test
    void generate_nanoBanana_decodesInlineImageData() {
        byte[] imageBytes = { 'N', 'A', 'N', 'O', 1, 1 };
        String b64 = Base64.getEncoder().encodeToString(imageBytes);

        // nano-banana routes to the :generateContent endpoint; the image
        // comes back inline as candidates[].content.parts[].inlineData.
        wm.stubFor(post(urlPathEqualTo("/v1beta/models/gemini-3-pro-image-preview:generateContent"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"candidates\":[{\"content\":{\"parts\":[{\"inlineData\":"
                                + "{\"mimeType\":\"image/png\",\"data\":\"" + b64 + "\"}}]}}]}")));

        ImageRequest req = new ImageRequest("nano-banana", "A neon city",
                "1024x1024", 1, null, null);
        List<GeneratedImage> out = provider.generate(req).block(Duration.ofSeconds(5));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).bytes()).isEqualTo(imageBytes);
        assertThat(out.get(0).mimeType()).isEqualTo("image/png");

        wm.verify(postRequestedFor(urlPathEqualTo("/v1beta/models/gemini-3-pro-image-preview:generateContent"))
                .withRequestBody(matchingJsonPath("$.contents[0].parts[0].text", equalTo("A neon city"))));
    }
}
