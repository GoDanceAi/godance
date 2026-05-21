package ai.godance.gateway.providers;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import ai.godance.config.AlibabaProperties;
import ai.godance.domain.Provider;
import ai.godance.gateway.ImageDtos.GeneratedImage;
import ai.godance.gateway.ImageDtos.ImageRequest;
import ai.godance.gateway.ImageProviderAdapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Alibaba DashScope — Qwen-Image text-to-image. DashScope image gen is
 * an async task:
 * <ol>
 *   <li>POST {@code /api/v1/services/aigc/text2image/image-synthesis}
 *       with header {@code X-DashScope-Async: enable} → {@code {output:{task_id}}}</li>
 *   <li>GET {@code /api/v1/tasks/{task_id}} polls until {@code SUCCEEDED}
 *       / {@code FAILED} / {@code UNKNOWN}.</li>
 *   <li>On {@code SUCCEEDED}, each {@code output.results[].url} is a
 *       presigned URL — fetch the bytes and re-host in our own bucket.</li>
 * </ol>
 *
 * <p>Synchronous-looking from the caller: the {@link #generate} Mono
 * doesn't emit until the poll loop terminates or hits {@code POLL_TIMEOUT}.
 *
 * <p>Reuses the shared {@link AlibabaProperties} block — Qwen text uses
 * its OpenAI-compatible mode ({@code .../compatible-mode}), but the native
 * task API is rooted at the bare host, so that suffix is stripped here.
 */
@Component
public class AlibabaQwenImageProvider implements ImageProviderAdapter {

    private static final Logger log = LoggerFactory.getLogger(AlibabaQwenImageProvider.class);
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(2);
    private static final Duration POLL_TIMEOUT = Duration.ofMinutes(5);
    private static final String STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_RUNNING = "RUNNING";

    private final WebClient apiClient;
    private final WebClient downloadClient;
    private final Duration pollInterval;

    @Autowired
    public AlibabaQwenImageProvider(AlibabaProperties props) {
        this(props, DEFAULT_POLL_INTERVAL);
    }

    /** Test seam — lets WireMock tests poll fast instead of waiting whole seconds. */
    AlibabaQwenImageProvider(AlibabaProperties props, Duration pollInterval) {
        this.pollInterval = pollInterval;
        this.apiClient = WebClient.builder()
                .baseUrl(nativeApiHost(props.apiBase()))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        // Separate client for downloading image bytes (presigned URL, no api-key).
        this.downloadClient = WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(32 * 1024 * 1024))
                .build();
    }

    @Override
    public Provider type() {
        return Provider.ALIBABA_DASHSCOPE;
    }

    @Override
    public Mono<List<GeneratedImage>> generate(ImageRequest request) {
        QwenSubmit body = new QwenSubmit(
                request.model(),
                new QwenInput(request.prompt()),
                new QwenParameters(toDashScopeSize(request.size()),
                        request.n() == null ? 1 : request.n()));

        return apiClient.post()
                .uri("/api/v1/services/aigc/text2image/image-synthesis")
                .header("X-DashScope-Async", "enable")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(QwenTaskResponse.class)
                .flatMap(submitted -> pollUntilDone(taskId(submitted)).timeout(POLL_TIMEOUT))
                .flatMap(this::downloadAll);
    }

    private Mono<QwenTaskResponse> pollUntilDone(String taskId) {
        return apiClient.get()
                .uri("/api/v1/tasks/{id}", taskId)
                .retrieve()
                .bodyToMono(QwenTaskResponse.class)
                .flatMap(r -> {
                    String status = r.output() == null || r.output().taskStatus() == null
                            ? "" : r.output().taskStatus();
                    return switch (status) {
                        case STATUS_SUCCEEDED -> Mono.just(r);
                        case STATUS_PENDING, STATUS_RUNNING -> Mono.delay(pollInterval)
                                .then(Mono.defer(() -> pollUntilDone(taskId)));
                        default -> {
                            log.warn("DashScope task {} terminated with status: {}", taskId, status);
                            yield Mono.error(new IllegalStateException(
                                    "Alibaba DashScope: " + status + " for task " + taskId));
                        }
                    };
                });
    }

    private Mono<List<GeneratedImage>> downloadAll(QwenTaskResponse r) {
        List<QwenResult> results = r.output() == null ? null : r.output().results();
        if (results == null || results.isEmpty()) {
            return Mono.error(new IllegalStateException(
                    "DashScope task succeeded but output.results is empty"));
        }
        return Flux.fromIterable(results)
                .concatMap(res -> downloadBytes(res.url()))
                .collectList();
    }

    private Mono<GeneratedImage> downloadBytes(String url) {
        if (url == null || url.isBlank()) {
            return Mono.error(new IllegalStateException("DashScope result missing image URL"));
        }
        return downloadClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(byte[].class)
                .map(bytes -> new GeneratedImage(bytes, "image/png", null));
    }

    private static String taskId(QwenTaskResponse r) {
        if (r.output() == null || r.output().taskId() == null) {
            throw new IllegalStateException("DashScope submit returned no task_id");
        }
        return r.output().taskId();
    }

    /** DashScope sizes use {@code WxH} with a {@code *} separator, e.g. {@code 1024*1024}. */
    private static String toDashScopeSize(String size) {
        if (size == null || size.isBlank()) return "1024*1024";
        return size.toLowerCase().replace('x', '*');
    }

    /**
     * The shared {@code alibaba} block points at the OpenAI-compatible mode
     * ({@code .../compatible-mode}); the native task API is rooted at the
     * bare host. Strip the suffix if present (no-op for a WireMock base URL).
     */
    private static String nativeApiHost(String apiBase) {
        String base = apiBase.endsWith("/") ? apiBase.substring(0, apiBase.length() - 1) : apiBase;
        if (base.endsWith("/compatible-mode")) {
            return base.substring(0, base.length() - "/compatible-mode".length());
        }
        return base;
    }

    // ----- DashScope wire shapes --------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record QwenSubmit(String model, QwenInput input, QwenParameters parameters) { }

    record QwenInput(String prompt) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record QwenParameters(String size, Integer n) { }

    record QwenTaskResponse(QwenOutput output) { }

    record QwenOutput(
            @JsonProperty("task_id") String taskId,
            @JsonProperty("task_status") String taskStatus,
            List<QwenResult> results
    ) { }

    record QwenResult(String url) { }
}
