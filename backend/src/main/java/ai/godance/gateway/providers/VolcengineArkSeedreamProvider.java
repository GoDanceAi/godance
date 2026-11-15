package ai.godance.gateway.providers;

import java.util.Base64;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import ai.godance.config.VolcengineProperties;
import ai.godance.domain.Provider;
import ai.godance.gateway.ImageDtos.GeneratedImage;
import ai.godance.gateway.ImageDtos.ImageInput;
import ai.godance.gateway.ImageDtos.ImageRequest;
import ai.godance.gateway.ImageProviderAdapter;
import reactor.core.publisher.Mono;

/**
 * Volcengine Ark — Seedream text-to-image. Ark exposes an
 * OpenAI-compatible images endpoint, {@code POST /api/v3/images/generations},
 * that returns the finished image in one call (unlike the Seedance video
 * task API). We request {@code response_format=b64_json} so we get raw
 * bytes back and re-host them in our own bucket.
 *
 * <p>Reuses the shared {@link VolcengineProperties} block (also used by
 * Doubao text + Seedance video). The catalog model name is passed through
 * as the Ark {@code model}; a production deployment may need a versioned
 * model id or endpoint id — a WireMock cassette will lock the real shape.
 */
@Component
public class VolcengineArkSeedreamProvider implements ImageProviderAdapter {

    private final WebClient client;
    private final VolcengineProperties props;

    public VolcengineArkSeedreamProvider(VolcengineProperties props) {
        this.props = props;
        this.client = WebClient.builder()
                .baseUrl(props.apiBase())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                // b64 image payloads run a few MB; lift the 256 KB default cap.
                .codecs(c -> c.defaultCodecs().maxInMemorySize(32 * 1024 * 1024))
                .build();
    }

    @Override
    public Provider type() {
        return Provider.VOLCENGINE_ARK;
    }

    @Override
    public boolean editsImages() {
        return true;
    }

    /**
     * An edit is the same request with {@code image}: one input image as a
     * base64 data URL, or a list of them, in the order given. Seedream takes
     * no mask. A request without input images is sent exactly as before.
     * Source: Ark's image generation reference
     * (volcengine.com/docs/82379/1541523), read 2026-11-14.
     */
    @Override
    public Mono<List<GeneratedImage>> generate(ImageRequest request) {
        ArkImageRequest body = new ArkImageRequest(
                request.model(),
                request.prompt(),
                request.size() == null ? "1024x1024" : request.size(),
                request.n() == null ? 1 : request.n(),
                "b64_json",
                imageOf(request));

        return client.post()
                .uri("/api/v3/images/generations")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(ArkImageResponse.class)
                .map(VolcengineArkSeedreamProvider::toDomain)
                .timeout(props.pollTimeout());
    }

    private static List<GeneratedImage> toDomain(ArkImageResponse r) {
        if (r.data() == null) return List.of();
        return r.data().stream()
                .map(d -> new GeneratedImage(
                        Base64.getDecoder().decode(d.b64Json()),
                        "image/png",
                        null))
                .toList();
    }

    /** Null for no input image, a string for one, a list for several: the three forms Ark's {@code image} takes. */
    private static Object imageOf(ImageRequest request) {
        if (!request.isEdit()) return null;
        List<String> urls = request.images().stream().map(VolcengineArkSeedreamProvider::dataUrl).toList();
        return urls.size() == 1 ? urls.get(0) : urls;
    }

    private static String dataUrl(ImageInput image) {
        return "data:" + image.contentType() + ";base64," + Base64.getEncoder().encodeToString(image.bytes());
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ArkImageRequest(
            String model,
            String prompt,
            String size,
            int n,
            @JsonProperty("response_format") String responseFormat,
            Object image
    ) { }

    record ArkImageResponse(Long created, List<ArkImageData> data) { }

    record ArkImageData(
            @JsonProperty("b64_json") String b64Json,
            String url
    ) { }
}
