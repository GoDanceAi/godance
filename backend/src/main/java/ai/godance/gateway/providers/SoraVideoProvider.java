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

import ai.godance.config.OpenAIProperties;
import ai.godance.domain.Provider;
import ai.godance.gateway.VideoDtos.GeneratedVideo;
import ai.godance.gateway.VideoDtos.VideoRequest;
import ai.godance.gateway.VideoProviderAdapter;
import reactor.core.publisher.Mono;

/**
 * OpenAI Sora video API — serves {@code sora-2}, {@code sora-2-pro}, and
 * {@code sora-2-pro-hd} (a Godance catalog alias mapped to {@code sora-2-pro}
 * at 1024p HD — the $0.50/s tier). Catalog model names are forwarded as the
 * upstream {@code model}; the HD alias is remapped first. Async submit + poll
 * pattern:
 * <ol>
 *   <li>POST {@code /v1/videos} → returns video {@code id} + status</li>
 *   <li>GET {@code /v1/videos/{id}} polls until {@code completed} / {@code failed}</li>
 *   <li>GET {@code /v1/videos/{id}/content} on success → mp4 bytes</li>
 * </ol>
 *
 * <p>Reuses {@link OpenAIProperties} (same api-key / api-base as the
 * text + image OpenAI adapters). Poll interval tuned for video latency
 * (Sora typically takes 1–5 minutes).
 */
@Component
public class SoraVideoProvider implements VideoProviderAdapter {

    private static final Logger log = LoggerFactory.getLogger(SoraVideoProvider.class);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(5);
    private static final Duration POLL_TIMEOUT = Duration.ofMinutes(10);
    private static final String STATUS_COMPLETED = "completed";

    private final WebClient client;

    public SoraVideoProvider(OpenAIProperties props) {
        this.client = WebClient.builder()
                .baseUrl(props.apiBase())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(256 * 1024 * 1024))
                .build();
    }

    @Override
    public Provider type() {
        return Provider.OPENAI;
    }

    @Override
    public Mono<List<GeneratedVideo>> generate(VideoRequest request) {
        // "sora-2-pro-hd" is a Godance catalog alias for Sora 2 Pro at 1024p HD
        // (the $0.50/s tier). There is no distinct upstream model id, so map it to
        // "sora-2-pro" and default to the HD size when the caller didn't set one.
        boolean hd = "sora-2-pro-hd".equals(request.model());
        String upstreamModel = hd ? "sora-2-pro" : request.model();
        String defaultSize = hd ? "1792x1024" : "1280x720";

        SoraSubmit body = new SoraSubmit(
                upstreamModel,
                request.prompt(),
                request.durationSeconds() == null ? 10 : request.durationSeconds(),
                request.size() == null ? defaultSize : request.size());

        return client.post()
                .uri("/v1/videos")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(SoraVideoResource.class)
                .flatMap(initial -> pollUntilDone(initial.id())
                        .timeout(POLL_TIMEOUT)
                        .flatMap(this::downloadContent)
                        .map(bytes -> List.of(new GeneratedVideo(bytes, "video/mp4"))));
    }

    private Mono<SoraVideoResource> pollUntilDone(String id) {
        return client.get()
                .uri("/v1/videos/{id}", id)
                .retrieve()
                .bodyToMono(SoraVideoResource.class)
                .flatMap(r -> {
                    String status = r.status() == null ? "" : r.status();
                    return switch (status) {
                        case STATUS_COMPLETED -> Mono.just(r);
                        case "queued", "in_progress", "processing" ->
                                Mono.delay(POLL_INTERVAL)
                                        .then(Mono.defer(() -> pollUntilDone(id)));
                        default -> {
                            log.warn("Sora video {} terminal status: {}", id, status);
                            yield Mono.error(new IllegalStateException(
                                    "Sora: " + status + " for video " + id));
                        }
                    };
                });
    }

    private Mono<byte[]> downloadContent(SoraVideoResource r) {
        return client.get()
                .uri("/v1/videos/{id}/content", r.id())
                .retrieve()
                .bodyToMono(byte[].class);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SoraSubmit(String model, String prompt, int seconds, String size) { }

    record SoraVideoResource(String id, String status) { }
}
