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

import ai.godance.config.VolcengineProperties;
import ai.godance.domain.Provider;
import ai.godance.gateway.VideoDtos.GeneratedVideo;
import ai.godance.gateway.VideoDtos.VideoRequest;
import ai.godance.gateway.VideoProviderAdapter;
import reactor.core.publisher.Mono;

/**
 * Volcengine Ark Seedance video — currently {@code seedance-2.0}.
 * Async video task pattern (mirrors BFL conceptually):
 * <ol>
 *   <li>POST {@code /api/v3/contents/generations/tasks} → {@code {id}}</li>
 *   <li>GET {@code /api/v3/contents/generations/tasks/{id}} polls until
 *       {@code succeeded} / {@code failed} / {@code cancelled}.</li>
 *   <li>{@code content.video_url} on success → fetch bytes.</li>
 * </ol>
 *
 * <p>API surface is simplified for v1; the real Ark endpoint may require
 * additional signing or model-specific payload tweaks. WireMock-recorded
 * cassettes will lock in the real shape when we wire production keys.
 */
@Component
public class VolcengineArkVideoProvider implements VideoProviderAdapter {

    private static final Logger log = LoggerFactory.getLogger(VolcengineArkVideoProvider.class);
    private static final String STATUS_SUCCEEDED = "succeeded";

    private final WebClient apiClient;
    private final WebClient downloadClient;
    private final VolcengineProperties props;

    public VolcengineArkVideoProvider(VolcengineProperties props) {
        this.props = props;
        this.apiClient = WebClient.builder()
                .baseUrl(props.apiBase())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
                .build();
        // Separate client for downloading the video bytes (sample URL is presigned).
        this.downloadClient = WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(256 * 1024 * 1024))
                .build();
    }

    @Override
    public Provider type() {
        return Provider.VOLCENGINE_ARK;
    }

    @Override
    public Mono<List<GeneratedVideo>> generate(VideoRequest request) {
        ArkSubmit body = new ArkSubmit(
                request.model(),
                List.of(new ArkContent("text", request.prompt())),
                request.durationSeconds() == null ? 5 : request.durationSeconds(),
                request.size() == null ? "1280x720" : request.size());

        return apiClient.post()
                .uri("/api/v3/contents/generations/tasks")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(ArkSubmitResponse.class)
                .flatMap(submitted -> pollUntilDone(submitted.id())
                        .timeout(props.pollTimeout())
                        .flatMap(this::downloadBytes)
                        .map(bytes -> List.of(new GeneratedVideo(bytes, "video/mp4"))));
    }

    private Mono<ArkTaskResponse> pollUntilDone(String id) {
        return apiClient.get()
                .uri("/api/v3/contents/generations/tasks/{id}", id)
                .retrieve()
                .bodyToMono(ArkTaskResponse.class)
                .flatMap(r -> {
                    String status = r.status() == null ? "" : r.status();
                    return switch (status) {
                        case STATUS_SUCCEEDED -> Mono.just(r);
                        case "queued", "running" -> Mono.delay(props.pollInterval())
                                .then(Mono.defer(() -> pollUntilDone(id)));
                        default -> {
                            log.warn("Volcengine task {} terminal status: {}", id, status);
                            yield Mono.error(new IllegalStateException(
                                    "Volcengine Ark: " + status + " for task " + id));
                        }
                    };
                });
    }

    private Mono<byte[]> downloadBytes(ArkTaskResponse r) {
        Object url = r.content() == null ? null : r.content().get("video_url");
        if (!(url instanceof String s) || s.isBlank()) {
            return Mono.error(new IllegalStateException(
                    "Volcengine task succeeded but content.video_url is missing"));
        }
        return downloadClient.get().uri(s).retrieve().bodyToMono(byte[].class);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ArkSubmit(String model, List<ArkContent> content, int duration, String ratio) { }

    record ArkContent(String type, String text) { }

    record ArkSubmitResponse(String id) { }

    record ArkTaskResponse(
            String id,
            String status,
            @JsonProperty("content") Map<String, Object> content
    ) { }
}
