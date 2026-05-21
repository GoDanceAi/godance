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

import ai.godance.config.StabilityProperties;
import ai.godance.gateway.ImageDtos.GeneratedImage;
import ai.godance.gateway.ImageDtos.ImageRequest;

class StabilitySdxlProviderTest {

    private WireMockServer wm;
    private StabilitySdxlProvider provider;

    @BeforeEach
    void start() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        provider = new StabilitySdxlProvider(
                new StabilityProperties("sk-stability-test", wm.baseUrl(), Duration.ofSeconds(10)));
    }

    @AfterEach
    void stop() {
        wm.stop();
    }

    @Test
    void generate_decodesArtifactBase64_intoBytes() {
        byte[] imageBytes = { 'S', 'D', 'X', 'L', 0, 1, 0 };
        String b64 = Base64.getEncoder().encodeToString(imageBytes);

        wm.stubFor(post(urlEqualTo("/v1/generation/stable-diffusion-xl-1024-v1-0/text-to-image"))
                .withHeader("Authorization", equalTo("Bearer sk-stability-test"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"artifacts\":[{\"base64\":\"" + b64
                                + "\",\"finishReason\":\"SUCCESS\",\"seed\":42}]}")));

        ImageRequest req = new ImageRequest("sdxl", "A desert at dusk",
                "1024x1024", 1, null, null);
        List<GeneratedImage> out = provider.generate(req).block(Duration.ofSeconds(5));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).bytes()).isEqualTo(imageBytes);
        assertThat(out.get(0).mimeType()).isEqualTo("image/png");

        wm.verify(postRequestedFor(urlEqualTo("/v1/generation/stable-diffusion-xl-1024-v1-0/text-to-image"))
                .withRequestBody(matchingJsonPath("$.text_prompts[0].text", equalTo("A desert at dusk"))));
    }
}
