package ai.godance.gateway.providers;

import java.util.Base64;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import ai.godance.config.StabilityProperties;
import ai.godance.domain.Provider;
import ai.godance.gateway.ImageDtos.GeneratedImage;
import ai.godance.gateway.ImageDtos.ImageRequest;
import ai.godance.gateway.ImageProviderAdapter;
import reactor.core.publisher.Mono;

/**
 * Stability AI — SDXL text-to-image. The v1 endpoint
 * {@code POST /v1/generation/{engine}/text-to-image} returns base64
 * artifacts synchronously (no poll loop). We re-host the bytes in our
 * own bucket.
 *
 * <p>SDXL 1.0 only accepts a fixed set of {@code width}×{@code height}
 * pairs; {@code 1024x1024} is the default. An unsupported size is
 * surfaced as an upstream 400 → the job fails and the cost is refunded.
 */
@Component
public class StabilitySdxlProvider implements ImageProviderAdapter {

    private static final String SDXL_ENGINE = "stable-diffusion-xl-1024-v1-0";

    private final WebClient client;
    private final StabilityProperties props;

    public StabilitySdxlProvider(StabilityProperties props) {
        this.props = props;
        this.client = WebClient.builder()
                .baseUrl(props.apiBase())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiKey())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                // b64 artifacts run a few MB; lift the 256 KB default cap.
                .codecs(c -> c.defaultCodecs().maxInMemorySize(32 * 1024 * 1024))
                .build();
    }

    @Override
    public Provider type() {
        return Provider.STABILITY;
    }

    @Override
    public Mono<List<GeneratedImage>> generate(ImageRequest request) {
        Dimensions d = parseSize(request.size());
        SdxlRequest body = new SdxlRequest(
                List.of(new TextPrompt(request.prompt())),
                request.n() == null ? 1 : request.n(),
                d.width(), d.height());

        return client.post()
                .uri("/v1/generation/{engine}/text-to-image", SDXL_ENGINE)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(SdxlResponse.class)
                .map(StabilitySdxlProvider::toDomain)
                .timeout(props.timeout());
    }

    private static List<GeneratedImage> toDomain(SdxlResponse r) {
        if (r.artifacts() == null) return List.of();
        return r.artifacts().stream()
                .map(a -> new GeneratedImage(
                        Base64.getDecoder().decode(a.base64()),
                        "image/png",
                        null))
                .toList();
    }

    private record Dimensions(int width, int height) { }

    private static Dimensions parseSize(String size) {
        if (size == null || size.isBlank()) return new Dimensions(1024, 1024);
        String[] parts = size.toLowerCase().split("x");
        if (parts.length != 2) {
            throw new IllegalArgumentException("size must be WxH (e.g. 1024x1024); got '" + size + "'");
        }
        try {
            return new Dimensions(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("size must be WxH integers; got '" + size + "'", e);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SdxlRequest(
            @JsonProperty("text_prompts") List<TextPrompt> textPrompts,
            int samples,
            int width,
            int height
    ) { }

    record TextPrompt(String text) { }

    record SdxlResponse(List<SdxlArtifact> artifacts) { }

    record SdxlArtifact(
            String base64,
            @JsonProperty("finishReason") String finishReason,
            Long seed
    ) { }
}
