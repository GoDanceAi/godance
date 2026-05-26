package ai.godance.gateway.providers;

import java.util.Base64;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import ai.godance.config.OpenAIProperties;
import ai.godance.domain.Provider;
import ai.godance.gateway.ImageDtos.GeneratedImage;
import ai.godance.gateway.ImageDtos.ImageRequest;
import ai.godance.gateway.ImageProviderAdapter;
import reactor.core.publisher.Mono;

/**
 * OpenAI {@code /v1/images/generations} for the {@code gpt-image-*}
 * family ({@code gpt-image-2}, {@code gpt-image-1.5}) — the catalog model
 * name is forwarded verbatim as the upstream {@code model}. These models
 * return base64 image data
 * by default — {@code response_format} and {@code style} are dall-e-only
 * params and are rejected for {@code gpt-image-*} — so we get the raw
 * bytes back and re-host them in our own bucket (we control URL
 * lifetime; OpenAI's URLs expire fast and CORS varies).
 */
@Component
public class OpenAIImageProvider implements ImageProviderAdapter {

    private final WebClient client;
    private final OpenAIProperties props;

    public OpenAIImageProvider(OpenAIProperties props) {
        this.props = props;
        this.client = WebClient.builder()
                .baseUrl(props.apiBase())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                // Image payloads can be ~1–4 MB per image at b64; bump the default 256 KB cap.
                .codecs(c -> c.defaultCodecs().maxInMemorySize(32 * 1024 * 1024))
                .build();
    }

    @Override
    public Provider type() {
        return Provider.OPENAI;
    }

    @Override
    public Mono<List<GeneratedImage>> generate(ImageRequest request) {
        OpenAIImageRequest body = new OpenAIImageRequest(
                request.model(),
                request.prompt(),
                request.n() == null ? 1 : request.n(),
                request.size(),
                request.quality());

        return client.post()
                .uri("/v1/images/generations")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(OpenAIImageResponse.class)
                .map(OpenAIImageProvider::toDomain)
                .timeout(props.timeout());
    }

    private static List<GeneratedImage> toDomain(OpenAIImageResponse r) {
        if (r.data() == null) return List.of();
        return r.data().stream()
                .map(d -> new GeneratedImage(
                        Base64.getDecoder().decode(d.b64Json()),
                        "image/png",     // OpenAI returns PNG
                        d.revisedPrompt()))
                .toList();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record OpenAIImageRequest(
            String model,
            String prompt,
            int n,
            String size,
            String quality
    ) { }

    record OpenAIImageResponse(Long created, List<OpenAIImageData> data) { }

    record OpenAIImageData(
            @JsonProperty("b64_json") String b64Json,
            String url,
            @JsonProperty("revised_prompt") String revisedPrompt
    ) { }
}
