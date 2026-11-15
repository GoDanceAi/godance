package ai.godance.gateway.providers;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import ai.godance.gateway.VideoDtos.VideoInput;
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
 *
 * <p><b>Images.</b> A clip may start from a first frame, end on a last
 * frame, or follow reference images. Each goes into {@code content} after
 * the text, as an {@code image_url} item with a {@code role} of
 * {@code first_frame}, {@code last_frame} or {@code reference_image},
 * holding the image itself as a base64 data URL. A prompt refers to the
 * images by position ("Image 1" is the first image item), so the order of
 * the request's reference images is the order sent. A request without
 * images is sent exactly as before: the one text item.
 * Source: Ark's "create a video generation task" reference
 * (docs.volcengine.com/docs/ark/create-video-generation-task-api), read
 * 2026-11-14.
 */
@Component
public class VolcengineArkVideoProvider implements VideoProviderAdapter {

    private static final Logger log = LoggerFactory.getLogger(VolcengineArkVideoProvider.class);
    private static final String STATUS_SUCCEEDED = "succeeded";
    private static final int DEFAULT_DURATION_SECONDS = 5;

    /**
     * What Ark documents for an input image: JPEG, PNG or WebP (it takes
     * more, but these are the types an input can be); a width-to-height
     * ratio between 0.4 and 2.5; under 30 MB. A first frame, a first and a
     * last frame, and reference images are separate modes that cannot be
     * mixed. Ark crops a frame from its centre when its shape is not the
     * clip's; that is documented for callers, not prevented.
     */
    private static final InputRules INPUT_RULES = new InputRules(
            Set.of("image/jpeg", "image/png", "image/webp"), 0.4, 2.5, 30L * 1024 * 1024 - 1, false);

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
    public int defaultDurationSeconds() {
        return DEFAULT_DURATION_SECONDS;
    }

    @Override
    public InputRules inputRules() {
        return INPUT_RULES;
    }

    @Override
    public Mono<List<GeneratedVideo>> generate(VideoRequest request) {
        ArkSubmit body = new ArkSubmit(
                request.model(),
                contentOf(request),
                request.durationSeconds() == null ? DEFAULT_DURATION_SECONDS : request.durationSeconds(),
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

    /** The text, then each image in the order Ark counts them: first frame, last frame, reference images. */
    private static List<Object> contentOf(VideoRequest request) {
        List<Object> content = new ArrayList<>();
        content.add(new ArkContent("text", request.prompt()));
        if (request.firstFrame() != null) content.add(ArkImage.of(request.firstFrame(), "first_frame"));
        if (request.lastFrame() != null) content.add(ArkImage.of(request.lastFrame(), "last_frame"));
        for (VideoInput reference : request.referenceImages()) content.add(ArkImage.of(reference, "reference_image"));
        return content;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ArkSubmit(String model, List<Object> content, int duration, String ratio) { }

    record ArkContent(String type, String text) { }

    /** An image item: the image as a data URL, and what it is to the clip. */
    record ArkImage(String type, @JsonProperty("image_url") ArkImageUrl imageUrl, String role) {
        static ArkImage of(VideoInput image, String role) {
            String url = "data:" + image.contentType() + ";base64," + Base64.getEncoder().encodeToString(image.bytes());
            return new ArkImage("image_url", new ArkImageUrl(url), role);
        }
    }

    record ArkImageUrl(String url) { }

    record ArkSubmitResponse(String id) { }

    record ArkTaskResponse(
            String id,
            String status,
            @JsonProperty("content") Map<String, Object> content
    ) { }
}
