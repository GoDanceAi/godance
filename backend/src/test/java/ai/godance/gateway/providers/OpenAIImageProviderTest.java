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

import ai.godance.config.OpenAIProperties;
import ai.godance.gateway.ImageDtos.GeneratedImage;
import ai.godance.gateway.ImageDtos.ImageRequest;

class OpenAIImageProviderTest {

    private WireMockServer wm;
    private OpenAIImageProvider provider;

    @BeforeEach
    void start() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        provider = new OpenAIImageProvider(
                new OpenAIProperties("sk-test", wm.baseUrl(), Duration.ofSeconds(10)));
    }

    @AfterEach
    void stop() {
        wm.stop();
    }

    @Test
    void generate_decodesB64Json_intoBytes_andPropagatesRevisedPrompt() {
        // A 1x1 transparent PNG, base64-encoded.
        byte[] pngBytes = new byte[] {
                (byte)0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D,
                0x49, 0x48, 0x44, 0x52, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
                0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, (byte)0xC4, (byte)0x89, 0x00, 0x00, 0x00,
                0x0A, 0x49, 0x44, 0x41, 0x54, 0x78, (byte)0x9C, 0x63, 0x00, 0x01, 0x00, 0x00,
                0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, (byte)0xB4, 0x00, 0x00, 0x00, 0x00, 0x49,
                0x45, 0x4E, 0x44, (byte)0xAE, 0x42, 0x60, (byte)0x82
        };
        String b64 = Base64.getEncoder().encodeToString(pngBytes);

        wm.stubFor(post(urlEqualTo("/v1/images/generations"))
                .withHeader("Authorization", equalTo("Bearer sk-test"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("""
                                {"created": 1700000000,
                                 "data": [{"b64_json": "%s", "revised_prompt": "A revised prompt"}]}
                                """.formatted(b64))));

        ImageRequest req = new ImageRequest("gpt-image-1", "A cat",
                "1024x1024", 1, null, null);
        List<GeneratedImage> out = provider.generate(req).block(Duration.ofSeconds(5));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).bytes()).isEqualTo(pngBytes);
        assertThat(out.get(0).mimeType()).isEqualTo("image/png");
        assertThat(out.get(0).revisedPrompt()).isEqualTo("A revised prompt");

        wm.verify(postRequestedFor(urlEqualTo("/v1/images/generations"))
                .withRequestBody(matchingJsonPath("$.response_format", equalTo("b64_json")))
                .withRequestBody(matchingJsonPath("$.prompt", equalTo("A cat"))));
    }
}
