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

import ai.godance.config.KlingProperties;
import ai.godance.domain.Provider;
import ai.godance.gateway.VideoDtos.GeneratedVideo;
import ai.godance.gateway.VideoDtos.VideoRequest;
import ai.godance.gateway.VideoProviderAdapter;
import reactor.core.publisher.Mono;

/**
 * Kling (Kuaishou) text-to-video. Async submit + poll:
 * <ol>
 *   <li>POST {@code /v1/videos/text2video} → {@code data.task_id}</li>
 *   <li>GET {@code /v1/videos/text2video/{task_id}} polls until
 *       {@code succeed} / {@code failed}.</li>
 *   <li>{@code data.task_result.videos[0].url} on success → fetch bytes.</li>
 * </ol>
 *
 * <p>China-hosted (Kuaishou), so {@code KLING} is Alicloud-allowed. The
 * API surface is simplified for v1; recorded cassettes will lock in the
 * real signing + payload shape when production keys land.
 */
@Component
public class KlingVideoProvider implements VideoProviderAdapter {

    private static final Logger log = LoggerFactory.getLogger(KlingVideoProvider.class);
    private static final String STATUS_SUCCEED = "succeed";
    private static final int DEFAULT_DURATION_SECONDS = 5;

    private final WebClient apiClient;
    private final WebClient downloadClient;
    private final KlingProperties props;

    public KlingVideoProvider(KlingProperties props) {
        this.props = props;
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
        return Provider.KLING;
    }

    @Override
    public Mono<List<GeneratedVideo>> generate(VideoRequest request) {
        KlingSubmit body = new KlingSubmit(
                request.model(),
                request.prompt(),
                request.durationSeconds() == null ? DEFAULT_DURATION_SECONDS : request.durationSeconds(),
                aspectRatio(request.size()));

        return apiClient.post()
                .uri("/v1/videos/text2video")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(KlingEnvelope.class)
                .flatMap(env -> {
                    String taskId = env.data() == null ? null : env.data().taskId();
                    if (taskId == null || taskId.isBlank()) {
                        return Mono.error(new IllegalStateException(
                                "Kling submit returned no task_id: " + env.message()));
                    }
                    return pollUntilDone(taskId).timeout(props.pollTimeout());
                })
                .flatMap(this::downloadBytes)
                .map(bytes -> List.of(new GeneratedVideo(bytes, "video/mp4")));
    }

    private Mono<KlingEnvelope> pollUntilDone(String taskId) {
        return apiClient.get()
                .uri("/v1/videos/text2video/{id}", taskId)
                .retrieve()
                .bodyToMono(KlingEnvelope.class)
                .flatMap(env -> {
                    String status = env.data() == null || env.data().taskStatus() == null
                            ? "" : env.data().taskStatus();
                    return switch (status) {
                        case STATUS_SUCCEED -> Mono.just(env);
                        case "submitted", "processing" -> Mono.delay(props.pollInterval())
                                .then(Mono.defer(() -> pollUntilDone(taskId)));
                        default -> {
                            log.warn("Kling task {} terminal status: {}", taskId, status);
                            yield Mono.error(new IllegalStateException(
                                    "Kling: " + status + " for task " + taskId));
                        }
                    };
                });
    }

    private Mono<byte[]> downloadBytes(KlingEnvelope env) {
        KlingTaskResult result = env.data() == null ? null : env.data().taskResult();
        List<KlingVideo> videos = result == null ? null : result.videos();
        if (videos == null || videos.isEmpty()
                || videos.get(0).url() == null || videos.get(0).url().isBlank()) {
            return Mono.error(new IllegalStateException(
                    "Kling task succeeded but task_result.videos is empty"));
        }
        return downloadClient.get().uri(videos.get(0).url()).retrieve().bodyToMono(byte[].class);
    }

    /** Kling takes an aspect ratio, not a pixel size. Pixel sizes are not mapped in v1. */
    private static String aspectRatio(String size) {
        if (size != null && size.contains(":")) return size;
        return "16:9";
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record KlingSubmit(
            @JsonProperty("model_name") String modelName,
            String prompt,
            int duration,
            @JsonProperty("aspect_ratio") String aspectRatio
    ) { }

    record KlingEnvelope(int code, String message, KlingData data) { }

    record KlingData(
            @JsonProperty("task_id") String taskId,
            @JsonProperty("task_status") String taskStatus,
            @JsonProperty("task_result") KlingTaskResult taskResult
    ) { }

    record KlingTaskResult(List<KlingVideo> videos) { }

    record KlingVideo(String url) { }
}
