package ai.godance.gateway.providers;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import ai.godance.config.GoogleProperties;
import ai.godance.domain.Provider;
import ai.godance.gateway.VideoDtos.GeneratedVideo;
import ai.godance.gateway.VideoDtos.VideoRequest;
import ai.godance.gateway.VideoProviderAdapter;
import reactor.core.publisher.Mono;

/**
 * Google Veo text-to-video via the Gemini API long-running-operation
 * pattern:
 * <ol>
 *   <li>POST {@code /v1beta/models/{model}:predictLongRunning?key=...} →
 *       {@code {name}} (operation handle)</li>
 *   <li>GET {@code /v1beta/{operation}?key=...} polls until {@code done}</li>
 *   <li>{@code response.generateVideoResponse.generatedSamples[0].video.uri}
 *       → fetch bytes.</li>
 * </ol>
 *
 * <p>Reuses {@link GoogleProperties} (same api-key / api-base as the
 * Gemini text adapter). Western provider: {@code GOOGLE_VERTEX} is never
 * Alicloud-allowed. Poll cadence is a constant — Veo runs minutes-long.
 */
@Component
public class GoogleVeoVideoProvider implements VideoProviderAdapter {

    private static final Logger log = LoggerFactory.getLogger(GoogleVeoVideoProvider.class);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(10);
    private static final Duration POLL_TIMEOUT = Duration.ofMinutes(10);
    private static final int DEFAULT_DURATION_SECONDS = 5;

    private final WebClient apiClient;
    private final WebClient downloadClient;
    private final GoogleProperties props;

    public GoogleVeoVideoProvider(GoogleProperties props) {
        this.props = props;
        this.apiClient = WebClient.builder()
                .baseUrl(props.apiBase())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
                .build();
        this.downloadClient = WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(256 * 1024 * 1024))
                .build();
    }

    @Override
    public Provider type() {
        return Provider.GOOGLE_VERTEX;
    }

    @Override
    public Mono<List<GeneratedVideo>> generate(VideoRequest request) {
        VeoSubmit body = new VeoSubmit(
                List.of(new VeoInstance(request.prompt())),
                new VeoParameters(
                        request.durationSeconds() == null
                                ? DEFAULT_DURATION_SECONDS : request.durationSeconds(),
                        "16:9"));
        String submitUri = UriComponentsBuilder
                .fromPath("/v1beta/models/{model}:predictLongRunning")
                .queryParam("key", props.apiKey())
                .buildAndExpand(request.model())
                .toUriString();

        return apiClient.post()
                .uri(submitUri)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(VeoOperation.class)
                .flatMap(op -> {
                    if (op.name() == null || op.name().isBlank()) {
                        return Mono.error(new IllegalStateException(
                                "Veo submit returned no operation name"));
                    }
                    return pollUntilDone(op.name()).timeout(POLL_TIMEOUT);
                })
                .flatMap(this::downloadBytes)
                .map(bytes -> List.of(new GeneratedVideo(bytes, "video/mp4")));
    }

    private Mono<VeoOperation> pollUntilDone(String operationName) {
        String uri = UriComponentsBuilder
                .fromPath("/v1beta/" + operationName)
                .queryParam("key", props.apiKey())
                .build()
                .toUriString();
        return apiClient.get()
                .uri(uri)
                .retrieve()
                .bodyToMono(VeoOperation.class)
                .flatMap(op -> {
                    if (op.error() != null) {
                        log.warn("Veo operation {} failed: {}", operationName, op.error());
                        return Mono.error(new IllegalStateException(
                                "Veo: operation " + operationName + " failed: " + op.error()));
                    }
                    if (Boolean.TRUE.equals(op.done())) {
                        return Mono.just(op);
                    }
                    return Mono.delay(POLL_INTERVAL)
                            .then(Mono.defer(() -> pollUntilDone(operationName)));
                });
    }

    private Mono<byte[]> downloadBytes(VeoOperation op) {
        String uri = extractUri(op);
        if (uri == null || uri.isBlank()) {
            return Mono.error(new IllegalStateException(
                    "Veo operation done but response carried no video URI"));
        }
        return downloadClient.get().uri(uri).retrieve().bodyToMono(byte[].class);
    }

    private static String extractUri(VeoOperation op) {
        VeoResponse response = op.response();
        if (response == null || response.generateVideoResponse() == null) return null;
        List<VeoSample> samples = response.generateVideoResponse().generatedSamples();
        if (samples == null || samples.isEmpty() || samples.get(0).video() == null) return null;
        return samples.get(0).video().uri();
    }

    // ----- Veo wire shapes --------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record VeoSubmit(List<VeoInstance> instances, VeoParameters parameters) { }

    record VeoInstance(String prompt) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record VeoParameters(
            @JsonProperty("durationSeconds") Integer durationSeconds,
            @JsonProperty("aspectRatio") String aspectRatio
    ) { }

    record VeoOperation(
            String name,
            Boolean done,
            VeoResponse response,
            Map<String, Object> error
    ) { }

    record VeoResponse(
            @JsonProperty("generateVideoResponse") VeoGenerateVideoResponse generateVideoResponse
    ) { }

    record VeoGenerateVideoResponse(
            @JsonProperty("generatedSamples") List<VeoSample> generatedSamples
    ) { }

    record VeoSample(VeoVideo video) { }

    record VeoVideo(String uri) { }
}
