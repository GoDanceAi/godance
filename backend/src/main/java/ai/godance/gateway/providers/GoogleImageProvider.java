package ai.godance.gateway.providers;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import ai.godance.config.GoogleProperties;
import ai.godance.domain.Provider;
import ai.godance.gateway.ImageDtos.GeneratedImage;
import ai.godance.gateway.ImageDtos.ImageRequest;
import ai.godance.gateway.ImageProviderAdapter;
import reactor.core.publisher.Mono;

/**
 * Google "Gemini Developer API" — image generation. Two model families
 * with two wire shapes, dispatched on the catalog model name:
 *
 * <ul>
 *   <li><b>Imagen</b> ({@code imagen-4}) — {@code POST .../{model}:predict},
 *       returns {@code predictions[].bytesBase64Encoded}.</li>
 *   <li><b>Nano Banana</b> ({@code nano-banana}) — {@code POST
 *       .../{model}:generateContent}, returns image bytes inline as
 *       {@code candidates[].content.parts[].inlineData.data}.</li>
 * </ul>
 *
 * <p>Reuses the shared {@link GoogleProperties} block (also used by the
 * Gemini text adapter). API-key auth via the {@code ?key=} query param.
 */
@Component
public class GoogleImageProvider implements ImageProviderAdapter {

    private final WebClient client;
    private final GoogleProperties props;

    public GoogleImageProvider(GoogleProperties props) {
        this.props = props;
        this.client = WebClient.builder()
                .baseUrl(props.apiBase())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                // b64 image payloads run a few MB; lift the 256 KB default cap.
                .codecs(c -> c.defaultCodecs().maxInMemorySize(32 * 1024 * 1024))
                .build();
    }

    @Override
    public Provider type() {
        return Provider.GOOGLE_VERTEX;
    }

    @Override
    public Mono<List<GeneratedImage>> generate(ImageRequest request) {
        return isImagen(request.model())
                ? generateImagen(request)
                : generateGemini(request);
    }

    // ----- Imagen :predict --------------------------------------------

    private Mono<List<GeneratedImage>> generateImagen(ImageRequest request) {
        ImagenRequest body = new ImagenRequest(
                List.of(new ImagenInstance(request.prompt())),
                new ImagenParameters(request.n() == null ? 1 : request.n()));
        String uri = UriComponentsBuilder.fromPath("/v1beta/models/{model}:predict")
                .queryParam("key", props.apiKey())
                .buildAndExpand(googleModelId(request.model()))
                .toUriString();
        return client.post()
                .uri(uri)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(ImagenResponse.class)
                .map(GoogleImageProvider::imagenToDomain)
                .timeout(props.timeout());
    }

    private static List<GeneratedImage> imagenToDomain(ImagenResponse r) {
        if (r.predictions() == null) return List.of();
        return r.predictions().stream()
                .map(p -> new GeneratedImage(
                        Base64.getDecoder().decode(p.bytesBase64Encoded()),
                        p.mimeType() == null ? "image/png" : p.mimeType(),
                        null))
                .toList();
    }

    // ----- Nano Banana :generateContent -------------------------------

    private Mono<List<GeneratedImage>> generateGemini(ImageRequest request) {
        GeminiImageRequest body = new GeminiImageRequest(List.of(
                new GeminiContent(List.of(new GeminiPart(request.prompt(), null)))));
        String uri = UriComponentsBuilder.fromPath("/v1beta/models/{model}:generateContent")
                .queryParam("key", props.apiKey())
                .buildAndExpand(googleModelId(request.model()))
                .toUriString();
        return client.post()
                .uri(uri)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(GeminiImageResponse.class)
                .map(GoogleImageProvider::geminiToDomain)
                .timeout(props.timeout());
    }

    private static List<GeneratedImage> geminiToDomain(GeminiImageResponse r) {
        if (r.candidates() == null) return List.of();
        List<GeneratedImage> out = new ArrayList<>();
        for (GeminiCandidate c : r.candidates()) {
            if (c.content() == null || c.content().parts() == null) continue;
            for (GeminiPart p : c.content().parts()) {
                GeminiInlineData inline = p.inlineData();
                if (inline != null && inline.data() != null) {
                    out.add(new GeneratedImage(
                            Base64.getDecoder().decode(inline.data()),
                            inline.mimeType() == null ? "image/png" : inline.mimeType(),
                            null));
                }
            }
        }
        return out;
    }

    // ----- Model routing ----------------------------------------------

    private static boolean isImagen(String catalogName) {
        return catalogName != null && catalogName.startsWith("imagen");
    }

    /** Map the Godance catalog model name to the Gemini API model id. */
    private static String googleModelId(String catalogName) {
        return switch (catalogName) {
            case "imagen-4" -> "imagen-4.0-generate-001";
            case "nano-banana" -> "gemini-3-pro-image-preview";
            default -> throw new IllegalArgumentException(
                    "Google image adapter doesn't know model: " + catalogName);
        };
    }

    // ----- Imagen wire shapes -----------------------------------------

    record ImagenRequest(List<ImagenInstance> instances, ImagenParameters parameters) { }

    record ImagenInstance(String prompt) { }

    record ImagenParameters(@JsonProperty("sampleCount") int sampleCount) { }

    record ImagenResponse(List<ImagenPrediction> predictions) { }

    record ImagenPrediction(
            @JsonProperty("bytesBase64Encoded") String bytesBase64Encoded,
            @JsonProperty("mimeType") String mimeType
    ) { }

    // ----- Gemini generateContent wire shapes -------------------------

    record GeminiImageRequest(List<GeminiContent> contents) { }

    record GeminiContent(List<GeminiPart> parts) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record GeminiPart(String text, @JsonProperty("inlineData") GeminiInlineData inlineData) { }

    record GeminiInlineData(
            @JsonProperty("mimeType") String mimeType,
            String data
    ) { }

    record GeminiImageResponse(List<GeminiCandidate> candidates) { }

    record GeminiCandidate(GeminiContent content) { }
}
