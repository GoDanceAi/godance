package ai.godance.gateway.providers;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import ai.godance.config.MiniMaxProperties;
import ai.godance.domain.Provider;
import ai.godance.gateway.VideoDtos.GeneratedVideo;
import ai.godance.gateway.VideoDtos.VideoRequest;
import ai.godance.gateway.VideoProviderAdapter;
import reactor.core.publisher.Mono;

/**
 * MiniMax Hailuo text-to-video. Async submit + poll + file retrieve:
 * <ol>
 *   <li>POST {@code /v1/video_generation} → {@code task_id}</li>
 *   <li>GET {@code /v1/query/video_generation?task_id=...} polls until
 *       {@code Success} / {@code Fail}; success carries a {@code file_id}.</li>
 *   <li>GET {@code /v1/files/retrieve?file_id=...} → {@code file.download_url}
 *       → fetch bytes.</li>
 * </ol>
 *
 * <p>China-hosted, so {@code MINIMAX} is Alicloud-allowed. Reuses the
 * shared {@link MiniMaxProperties} (the text MiniMax adapter binds the
 * same {@code godance.providers.minimax} block); poll cadence is a
 * constant here since video latency differs from chat.
 */
@Component
public class MiniMaxHailuoVideoProvider implements VideoProviderAdapter {

    private static final Logger log = LoggerFactory.getLogger(MiniMaxHailuoVideoProvider.class);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(5);
    private static final Duration POLL_TIMEOUT = Duration.ofMinutes(10);
    private static final String STATUS_SUCCESS = "Success";
    private static final int DEFAULT_DURATION_SECONDS = 6;

    private final WebClient apiClient;
    private final WebClient downloadClient;

    public MiniMaxHailuoVideoProvider(MiniMaxProperties props) {
        this.apiClient = WebClient.builder()
                .baseUrl(props.apiBase())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
                .build();
        this.downloadClient = WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(256 * 1024 * 1024))
                .build();
    }

    @Override
    public Provider type() {
        return Provider.MINIMAX;
    }

    @Override
    public Mono<List<GeneratedVideo>> generate(VideoRequest request) {
        MiniMaxSubmit body = new MiniMaxSubmit(
                request.model(),
                request.prompt(),
                request.durationSeconds() == null ? DEFAULT_DURATION_SECONDS : request.durationSeconds());

        return apiClient.post()
                .uri("/v1/video_generation")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(MiniMaxSubmitResponse.class)
                .flatMap(sub -> {
                    if (sub.taskId() == null || sub.taskId().isBlank()) {
                        return Mono.error(new IllegalStateException(
                                "MiniMax submit returned no task_id"));
                    }
                    return pollUntilDone(sub.taskId()).timeout(POLL_TIMEOUT);
                })
                .flatMap(this::retrieveFileUrl)
                .flatMap(url -> downloadClient.get().uri(url).retrieve().bodyToMono(byte[].class))
                .map(bytes -> List.of(new GeneratedVideo(bytes, "video/mp4")));
    }

    private Mono<MiniMaxQueryResponse> pollUntilDone(String taskId) {
        return apiClient.get()
                .uri(uri -> uri.path("/v1/query/video_generation")
                        .queryParam("task_id", taskId).build())
                .retrieve()
                .bodyToMono(MiniMaxQueryResponse.class)
                .flatMap(r -> {
                    String status = r.status() == null ? "" : r.status();
                    return switch (status) {
                        case STATUS_SUCCESS -> Mono.just(r);
                        case "Queueing", "Preparing", "Processing" -> Mono.delay(POLL_INTERVAL)
                                .then(Mono.defer(() -> pollUntilDone(taskId)));
                        default -> {
                            log.warn("MiniMax task {} terminal status: {}", taskId, status);
                            yield Mono.error(new IllegalStateException(
                                    "MiniMax: " + status + " for task " + taskId));
                        }
                    };
                });
    }

    private Mono<String> retrieveFileUrl(MiniMaxQueryResponse query) {
        if (query.fileId() == null || query.fileId().isBlank()) {
            return Mono.error(new IllegalStateException(
                    "MiniMax task succeeded but file_id is missing"));
        }
        return apiClient.get()
                .uri(uri -> uri.path("/v1/files/retrieve")
                        .queryParam("file_id", query.fileId()).build())
                .retrieve()
                .bodyToMono(MiniMaxFileResponse.class)
                .flatMap(f -> {
                    String url = f.file() == null ? null : f.file().downloadUrl();
                    if (url == null || url.isBlank()) {
                        return Mono.error(new IllegalStateException(
                                "MiniMax file retrieve returned no download_url"));
                    }
                    return Mono.just(url);
                });
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record MiniMaxSubmit(String model, String prompt, int duration) { }

    record MiniMaxSubmitResponse(@JsonProperty("task_id") String taskId) { }

    record MiniMaxQueryResponse(
            @JsonProperty("task_id") String taskId,
            String status,
            @JsonProperty("file_id") String fileId
    ) { }

    record MiniMaxFileResponse(MiniMaxFile file) { }

    record MiniMaxFile(@JsonProperty("download_url") String downloadUrl) { }
}
