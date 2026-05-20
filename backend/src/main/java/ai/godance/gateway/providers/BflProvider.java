package ai.godance.gateway.providers;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import ai.godance.config.BflProperties;
import ai.godance.domain.Provider;
import ai.godance.gateway.ImageDtos.GeneratedImage;
import ai.godance.gateway.ImageDtos.ImageRequest;
import ai.godance.gateway.ImageProviderAdapter;
import reactor.core.publisher.Mono;

/**
 * Black Forest Labs (Flux). Async pattern:
 * <ol>
 *   <li>{@code POST /v1/<model-endpoint>} → {@code {id, polling_url}}</li>
 *   <li>{@code GET /v1/get_result?id=...} returns {@code Pending} until
 *       the image is ready or {@code Error}/{@code *Moderated}</li>
 *   <li>On {@code Ready}, {@code result.sample} is a presigned URL we
 *       fetch the bytes from and re-host in our own bucket.</li>
 * </ol>
 *
 * <p>Synchronous-looking from the caller: the {@link #generate} Mono
 * doesn't emit until the poll loop terminates or hits {@code pollTimeout}.
 */
@Component
public class BflProvider implements ImageProviderAdapter {

    private static final Logger log = LoggerFactory.getLogger(BflProvider.class);
    private static final String STATUS_PENDING = "Pending";
    private static final String STATUS_READY = "Ready";

    private final WebClient apiClient;
    private final WebClient downloadClient;
    private final BflProperties props;

    public BflProvider(BflProperties props) {
        this.props = props;
        this.apiClient = WebClient.builder()
                .baseUrl(props.apiBase())
                .defaultHeader("x-key", props.apiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        // Separate client for downloading image bytes (no api-key header, no base url)
        this.downloadClient = WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(32 * 1024 * 1024))
                .build();
    }

    @Override
    public Provider type() {
        return Provider.BFL;
    }

    @Override
    public Mono<List<GeneratedImage>> generate(ImageRequest request) {
        String endpoint = "/v1/" + modelToEndpoint(request.model());
        var dimensions = parseSize(request.size());
        BflSubmit body = new BflSubmit(request.prompt(), dimensions.width(), dimensions.height());

        return apiClient.post()
                .uri(endpoint)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(BflSubmitResponse.class)
                .flatMap(submitted -> pollUntilDone(submitted.id())
                        .timeout(props.pollTimeout())
                        .flatMap(this::downloadBytes)
                        .map(bytes -> List.of(new GeneratedImage(bytes, "image/jpeg", null))));
    }

    private Mono<BflResultResponse> pollUntilDone(String id) {
        return apiClient.get()
                .uri(uri -> uri.path("/v1/get_result").queryParam("id", id).build())
                .retrieve()
                .bodyToMono(BflResultResponse.class)
                .flatMap(r -> switch (r.status() == null ? "" : r.status()) {
                    case STATUS_READY -> Mono.just(r);
                    case STATUS_PENDING -> Mono.delay(props.pollInterval())
                            .then(Mono.defer(() -> pollUntilDone(id)));
                    default -> {
                        log.warn("BFL job {} terminated with non-Ready status: {}", id, r.status());
                        yield Mono.error(new IllegalStateException(
                                "BFL: " + r.status() + " for job " + id));
                    }
                });
    }

    private Mono<byte[]> downloadBytes(BflResultResponse result) {
        Object sample = result.result() == null ? null : result.result().get("sample");
        if (!(sample instanceof String url) || url.isBlank()) {
            return Mono.error(new IllegalStateException("BFL result missing sample URL"));
        }
        return downloadClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(byte[].class);
    }

    /** Map our catalog model name to BFL's endpoint slug. */
    private static String modelToEndpoint(String modelName) {
        return switch (modelName) {
            case "flux-1.1-pro" -> "flux-pro-1.1";
            case "flux-2" -> "flux-2";
            default -> throw new IllegalArgumentException(
                    "BFL adapter doesn't know endpoint for model: " + modelName);
        };
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
    record BflSubmit(String prompt, int width, int height) { }

    record BflSubmitResponse(
            String id,
            @JsonProperty("polling_url") String pollingUrl
    ) { }

    record BflResultResponse(String id, String status, Map<String, Object> result) { }
}
