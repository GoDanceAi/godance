package ai.godance.gateway.providers;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import ai.godance.config.RunwayProperties;
import ai.godance.domain.Provider;
import ai.godance.gateway.VideoDtos.GeneratedVideo;
import ai.godance.gateway.VideoDtos.VideoRequest;
import ai.godance.gateway.VideoProviderAdapter;
import reactor.core.publisher.Mono;

/**
 * Runway (Gen-4) text-to-video. Async submit + poll:
 * <ol>
 *   <li>POST {@code /v1/text_to_video} → {@code {id}}</li>
 *   <li>GET {@code /v1/tasks/{id}} polls until {@code SUCCEEDED} /
 *       {@code FAILED} / {@code CANCELLED}.</li>
 *   <li>{@code output[0]} on success → fetch bytes.</li>
 * </ol>
 *
 * <p>Western provider: {@code RUNWAY} is never Alicloud-allowed. Auth is
 * a bearer token plus a pinned {@code X-Runway-Version} header.
 */
@Component
public class RunwayVideoProvider implements VideoProviderAdapter {

    private static final Logger log = LoggerFactory.getLogger(RunwayVideoProvider.class);
    private static final String RUNWAY_VERSION = "2024-11-06";
    private static final String STATUS_SUCCEEDED = "SUCCEEDED";
    private static final int DEFAULT_DURATION_SECONDS = 5;

    private final WebClient apiClient;
    private final WebClient downloadClient;
    private final RunwayProperties props;

    public RunwayVideoProvider(RunwayProperties props) {
        this.props = props;
        this.apiClient = WebClient.builder()
                .baseUrl(props.apiBase())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiKey())
                .defaultHeader("X-Runway-Version", RUNWAY_VERSION)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
                .build();
        this.downloadClient = WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(256 * 1024 * 1024))
                .build();
    }

    @Override
    public Provider type() {
        return Provider.RUNWAY;
    }

    @Override
    public Mono<List<GeneratedVideo>> generate(VideoRequest request) {
        RunwaySubmit body = new RunwaySubmit(
                request.model(),
                request.prompt(),
                request.durationSeconds() == null ? DEFAULT_DURATION_SECONDS : request.durationSeconds(),
                ratio(request.size()));

        return apiClient.post()
                .uri("/v1/text_to_video")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(RunwayTask.class)
                .flatMap(task -> {
                    if (task.id() == null || task.id().isBlank()) {
                        return Mono.error(new IllegalStateException(
                                "Runway submit returned no task id"));
                    }
                    return pollUntilDone(task.id()).timeout(props.pollTimeout());
                })
                .flatMap(this::downloadBytes)
                .map(bytes -> List.of(new GeneratedVideo(bytes, "video/mp4")));
    }

    private Mono<RunwayTask> pollUntilDone(String id) {
        return apiClient.get()
                .uri("/v1/tasks/{id}", id)
                .retrieve()
                .bodyToMono(RunwayTask.class)
                .flatMap(task -> {
                    String status = task.status() == null ? "" : task.status();
                    return switch (status) {
                        case STATUS_SUCCEEDED -> Mono.just(task);
                        case "PENDING", "RUNNING", "THROTTLED" -> Mono.delay(props.pollInterval())
                                .then(Mono.defer(() -> pollUntilDone(id)));
                        default -> {
                            log.warn("Runway task {} terminal status: {}", id, status);
                            yield Mono.error(new IllegalStateException(
                                    "Runway: " + status + " for task " + id));
                        }
                    };
                });
    }

    private Mono<byte[]> downloadBytes(RunwayTask task) {
        if (task.output() == null || task.output().isEmpty()
                || task.output().get(0) == null || task.output().get(0).isBlank()) {
            return Mono.error(new IllegalStateException(
                    "Runway task succeeded but output is empty"));
        }
        return downloadClient.get().uri(task.output().get(0)).retrieve().bodyToMono(byte[].class);
    }

    /** {@code VideoRequest.size} is {@code WxH}; Runway expects {@code W:H}. */
    private static String ratio(String size) {
        if (size == null || size.isBlank()) return "1280:720";
        return size.toLowerCase().replace("x", ":");
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record RunwaySubmit(
            String model,
            @JsonProperty("promptText") String promptText,
            int duration,
            String ratio
    ) { }

    record RunwayTask(String id, String status, List<String> output) { }
}
