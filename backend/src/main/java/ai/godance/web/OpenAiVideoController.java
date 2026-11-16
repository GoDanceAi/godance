package ai.godance.web;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.Part;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import ai.godance.domain.Modality;
import ai.godance.security.JwtService;
import ai.godance.service.InputRefusedException;
import ai.godance.service.OpenAiVideoRefusedException;
import ai.godance.service.OpenAiVideos;
import ai.godance.service.OpenAiVideos.Content;
import ai.godance.service.OpenAiVideos.Variant;
import ai.godance.service.RecipeRefusedException;
import ai.godance.service.RecipeRunService;
import ai.godance.service.VideoInputs;
import ai.godance.web.OpenAiVideoRequests.Fields;
import ai.godance.web.dto.InputDtos.InputRef;
import ai.godance.web.dto.OpenAiVideoDtos.Deleted;
import ai.godance.web.dto.OpenAiVideoDtos.InputReference;
import ai.godance.web.dto.OpenAiVideoDtos.Video;
import ai.godance.web.dto.OpenAiVideoDtos.VideoPage;
import ai.godance.web.dto.VideoDtos.VideoGenerationRequest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * OpenAI's Videos API, as a second view of Godance video jobs, so that
 * code written for OpenAI's SDK
 * ({@code client.videos.create_and_poll(…)},
 * {@code client.videos.download_content(…)}) works with the base URL and
 * key changed.
 *
 * <pre>
 * POST   /v1/videos                 JSON, or multipart with an input_reference file → the video, queued
 * GET    /v1/videos?limit=&amp;after=&amp;order=   the caller's videos, a page as OpenAI pages
 * GET    /v1/videos/{id}            one video
 * GET    /v1/videos/{id}/content?variant=video|thumbnail   the file's bytes, streamed
 * DELETE /v1/videos/{id}            deletes the files now; never a refund
 * </pre>
 *
 * <p><b>A video is a job.</b> Creating one calls the handler of
 * {@code POST /v1/videos/generations} itself, so the pre-debit, the origin,
 * {@code gallery_recipe}, {@code recipe} and the input rules are that
 * route's code, not a copy of it. Its id is the job's id, and the job is
 * at {@code /v1/jobs/{id}} as any job is ({@link OpenAiVideos}).
 *
 * <p><b>The request.</b> OpenAI's SDK always sends a multipart form, with
 * or without a file, so both forms are taken. {@code seconds} is a string
 * there ({@code "8"}); a number is taken too. {@code model} is a Godance
 * video model and has no default. {@code input_reference} becomes the
 * job's first frame under round 11's input rules: a file part is stored as
 * an upload to {@code POST /v1/files} is, and {@code file_id} (or the
 * Godance extension {@code job_output}) names an image already stored.
 * {@code image_url} is refused: Godance does not fetch input images. The
 * Godance extensions {@code last_frame} and {@code reference_images} are
 * input references, as on {@code POST /v1/videos/generations}.
 *
 * <p><b>Nothing is dropped without a word.</b> A field the route does not
 * read, {@code n} included, answers 400 {@code invalid_parameter} naming
 * it. In a form, the bracketed fields OpenAI's SDK writes a nested value as
 * ({@code recipe[version_id]}, {@code recipe[variables][subject]}) are
 * read back into that value ({@link OpenAiVideoRequests}).
 *
 * <p><b>Content is streamed, not redirected.</b> OpenAI's own client
 * follows redirects, but a caller who passes their own HTTP client, or
 * uses curl without {@code -L}, would save an empty body without an error.
 *
 * <p><b>What OpenAI offers and Godance does not</b> (remix, edits,
 * extensions, characters, the spritesheet variant) answers with a
 * {@code code} of its own rather than being ignored.
 *
 * <p>Errors are problem+json, as everywhere, and on these routes also
 * carry OpenAI's {@code error} object, which is where its SDKs read an
 * error's message ({@link ai.godance.security.OpenAiError}).
 */
@RestController
@RequestMapping("/v1")
public class OpenAiVideoController {

    /**
     * How long OpenAI's SDK waits between polls: it reads this header from a
     * retrieve whose video is not finished, and polls every second without
     * it. A video takes minutes.
     */
    static final String POLL_AFTER_HEADER = "openai-poll-after-ms";
    static final String POLL_AFTER_MS = "5000";

    private static final int STREAM_CHUNK_BYTES = 64 * 1024;

    /**
     * Stands in for the first frame a form brings as a file, which is not
     * stored yet, in the checks of which inputs a model takes: those look
     * at which inputs a request has, never at what they point to.
     */
    private static final InputRef FIRST_FRAME_TO_STORE = InputRef.file(new UUID(0L, 0L));

    private final VideoController generations;
    private final OpenAiVideos videos;
    private final VideoInputs videoInputs;
    private final RecipeRunService recipes;
    private final InputUploadIntake intake;
    private final OpenAiVideoRequests requests;

    public OpenAiVideoController(VideoController generations, OpenAiVideos videos, VideoInputs videoInputs,
                                 RecipeRunService recipes, InputUploadIntake intake, ObjectMapper json) {
        this.generations = generations;
        this.videos = videos;
        this.videoInputs = videoInputs;
        this.recipes = recipes;
        this.intake = intake;
        this.requests = new OpenAiVideoRequests(json);
    }

    // ===== Create =====================================================

    /** The body is read here rather than bound, so that a field the route does not read is refused, not skipped. */
    @PostMapping(value = "/videos", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Video> createJson(@RequestBody JsonNode body, @AuthenticationPrincipal Jwt jwt) {
        Fields fields = requests.json(body);
        requireWellFormed(fields);
        return create(jwt, fields, firstFrame(fields.inputReference()));
    }

    @PostMapping(value = "/videos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<Video> createMultipart(@AuthenticationPrincipal Jwt jwt, ServerWebExchange exchange) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        String ip = InputUploadIntake.clientIp(exchange);
        return InputUploads.form(exchange, OpenAiVideoController::unreadableForm)
                .flatMap(form -> {
                    Fields fields = requests.form(form);
                    requireWellFormed(fields);
                    Part file = form.getFirst(OpenAiVideoRequests.INPUT_REFERENCE);
                    InputRef named = firstFrame(fields.inputReference());
                    if (file == null) return create(jwt, fields, named);
                    if (named != null) {
                        throw OpenAiVideoRefusedException.invalid("input_reference",
                                "send input_reference once: a file part, or input_reference[file_id]");
                    }
                    // Everything that can refuse the request without its image does so first,
                    // so a request that cannot run leaves no stored file behind.
                    return Mono.fromRunnable(() -> {
                                videoInputs.requireMayUse(userId);
                                // A recipe with input slots brings its own images: the form's is then one too many.
                                if (fields.recipe() != null && recipes.resolve(userId, Modality.VIDEO, fields.prompt(),
                                        fields.recipe()).hasInputs()) {
                                    throw RecipeRefusedException.inputsOutsideRecipe();
                                }
                                // The model takes a first frame, and whatever else the form names beside it.
                                videoInputs.requireTakes(fields.model(), new VideoInputs.Refs(
                                        FIRST_FRAME_TO_STORE, fields.lastFrame(), fields.referenceImages()));
                            })
                            .subscribeOn(Schedulers.boundedElastic())
                            // The image is taken in exactly as an upload to POST /v1/files is.
                            .then(Mono.defer(() -> intake.admit(userId, ip)))
                            .then(Mono.defer(() -> intake.store(userId, ip, file)))
                            .flatMap(stored -> create(jwt, fields, InputRef.file(stored.getId())));
                })
                .onErrorMap(InputUploads::isTooLarge, e -> intake.tooLarge(userId, ip));
    }

    /**
     * A form that cannot be read at all, and why. The likeliest: more fields
     * than a form may have ({@code spring.webflux.multipart.max-parts}),
     * which a recipe of many variables reaches as bracketed fields, one per
     * value.
     */
    private static OpenAiVideoRefusedException unreadableForm(String why) {
        return OpenAiVideoRefusedException.invalid(null, why
                + " Send recipe or reference_images as JSON text, one field each, or send the request as JSON.");
    }

    /**
     * The job, made by the handler of {@code POST /v1/videos/generations},
     * then read back as a video.
     */
    private Mono<Video> create(Jwt jwt, Fields fields, InputRef firstFrame) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        VideoGenerationRequest request = new VideoGenerationRequest(fields.model(), fields.prompt(), fields.size(),
                fields.seconds(), null, fields.webhookUrl(), fields.galleryRecipe(), fields.recipe(),
                firstFrame, fields.lastFrame(), fields.referenceImages());
        return generations.generate(request, jwt)
                .flatMap(accepted -> Mono.fromCallable(() -> videos.accepted(userId, accepted.getBody().jobId()))
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    /** What {@code @Valid} checks on the generations request, which this route builds itself. */
    private static void requireWellFormed(Fields fields) {
        if (fields.model() == null || fields.model().isBlank()) {
            throw OpenAiVideoRefusedException.invalid("model",
                    "model is required: name a video model from GET /v1/models, such as seedance-2.0");
        }
        if (fields.seconds() != null && fields.seconds() < 1) {
            throw OpenAiVideoRefusedException.invalid("seconds", "seconds must be at least 1");
        }
    }

    /**
     * {@code input_reference} as the job's first frame; null when the request
     * has none. An {@code image_url} is refused. A reference that names
     * nothing, or both a file and a job output, is passed on and refused by
     * the input rules as {@code invalid_input_reference}.
     */
    private static InputRef firstFrame(InputReference reference) {
        if (reference == null) return null;
        if (reference.imageUrl() != null) throw OpenAiVideoRefusedException.imageUrlNotSupported();
        return new InputRef(reference.fileId() == null ? null : fileId(reference.fileId()), reference.jobOutput());
    }

    /** A malformed id cannot be anyone's file: the same 404 as an unknown one. */
    private static UUID fileId(String raw) {
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw InputRefusedException.fileNotFound();
        }
    }

    // ===== Read =======================================================

    @GetMapping("/videos")
    public Mono<VideoPage> list(@AuthenticationPrincipal Jwt jwt,
                                @RequestParam(required = false) String limit,
                                @RequestParam(required = false) String after,
                                @RequestParam(required = false) String order) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        Integer pageSize = parseLimit(limit);
        UUID cursor = parseAfter(after);
        boolean oldestFirst = parseOldestFirst(order);
        return Mono.fromCallable(() -> videos.list(userId, pageSize, cursor, oldestFirst))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/videos/{id}")
    public Mono<ResponseEntity<Video>> get(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        UUID videoId = videoId(id);
        return Mono.fromCallable(() -> videos.get(userId, videoId))
                .subscribeOn(Schedulers.boundedElastic())
                .map(video -> {
                    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
                    boolean open = video.status().equals("queued") || video.status().equals("in_progress");
                    if (open) response.header(POLL_AFTER_HEADER, POLL_AFTER_MS);
                    return response.body(video);
                });
    }

    /**
     * The file's bytes, streamed from storage with the content type it was
     * stored with. Never cached: the answer is the owner's alone.
     */
    @GetMapping("/videos/{id}/content")
    public Mono<Void> content(@PathVariable String id, @RequestParam(required = false) String variant,
                              @AuthenticationPrincipal Jwt jwt, ServerWebExchange exchange) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        UUID videoId = videoId(id);
        Variant which = Variant.parse(variant);
        return Mono.fromCallable(() -> videos.content(userId, videoId, which))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(content -> stream(content, exchange.getResponse()));
    }

    private static Mono<Void> stream(Content content, ServerHttpResponse response) {
        InputStream bytes = content.bytes();
        HttpHeaders headers = response.getHeaders();
        headers.setContentType(mediaType(content.contentType()));
        // Without a recorded size the response is sent chunked.
        if (content.sizeBytes() > 0) headers.setContentLength(content.sizeBytes());
        headers.setCacheControl(CacheControl.noStore().cachePrivate());
        Flux<DataBuffer> body = DataBufferUtils.readInputStream(() -> bytes, response.bufferFactory(), STREAM_CHUNK_BYTES)
                .subscribeOn(Schedulers.boundedElastic());
        // Reading to the end closes the storage stream. If the response fails or the client goes
        // away first, possibly before a byte was read, it is closed here.
        return response.writeWith(body)
                .doOnError(e -> close(bytes))
                .doOnCancel(() -> close(bytes));
    }

    private static MediaType mediaType(String stored) {
        try {
            return stored == null || stored.isBlank() ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(stored);
        } catch (IllegalArgumentException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private static void close(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // nothing left to release
        }
    }

    // ===== Delete =====================================================

    @DeleteMapping("/videos/{id}")
    public Mono<Deleted> delete(@PathVariable String id, @AuthenticationPrincipal Jwt jwt,
                                ServerWebExchange exchange) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        UUID videoId = videoId(id);
        String ip = InputUploadIntake.clientIp(exchange);
        return Mono.fromCallable(() -> videos.delete(userId, videoId, ip))
                .subscribeOn(Schedulers.boundedElastic());
    }

    // ===== What OpenAI's Videos API has and Godance does not ==========

    @PostMapping("/videos/{id}/remix")
    public Mono<Void> remix(@PathVariable String id) {
        throw OpenAiVideoRefusedException.notSupported(
                OpenAiVideoRefusedException.CODE_REMIX_NOT_SUPPORTED, "Remixing a video");
    }

    @PostMapping("/videos/edits")
    public Mono<Void> edit() {
        throw OpenAiVideoRefusedException.notSupported(
                OpenAiVideoRefusedException.CODE_EDITS_NOT_SUPPORTED, "Editing a video");
    }

    @PostMapping("/videos/extensions")
    public Mono<Void> extend() {
        throw OpenAiVideoRefusedException.notSupported(
                OpenAiVideoRefusedException.CODE_EXTENSIONS_NOT_SUPPORTED, "Extending a video");
    }

    @PostMapping("/videos/characters")
    public Mono<Void> createCharacter() {
        throw OpenAiVideoRefusedException.notSupported(
                OpenAiVideoRefusedException.CODE_CHARACTERS_NOT_SUPPORTED, "Creating a character");
    }

    @GetMapping("/videos/characters/{id}")
    public Mono<Void> getCharacter(@PathVariable String id) {
        throw OpenAiVideoRefusedException.notSupported(
                OpenAiVideoRefusedException.CODE_CHARACTERS_NOT_SUPPORTED, "Reading a character");
    }

    // ===== Parameters =================================================

    /** A malformed id cannot be anyone's video: the same 404 as an unknown one. */
    private static UUID videoId(String raw) {
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw OpenAiVideoRefusedException.notFound();
        }
    }

    private static Integer parseLimit(String raw) {
        if (raw == null) return null;
        try {
            int limit = Integer.parseInt(raw.trim());
            if (limit >= 1 && limit <= OpenAiVideos.MAX_LIMIT) return limit;
        } catch (NumberFormatException ignored) {
            // falls through to the 400
        }
        throw OpenAiVideoRefusedException.invalid("limit",
                "limit must be an integer between 1 and " + OpenAiVideos.MAX_LIMIT);
    }

    private static UUID parseAfter(String raw) {
        if (raw == null) return null;
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw OpenAiVideoRefusedException.invalid("after", "after must be the id of one of your videos");
        }
    }

    /** OpenAI's {@code order}: {@code desc} (newest first, the default) or {@code asc}. */
    private static boolean parseOldestFirst(String raw) {
        if (raw == null || raw.equals("desc")) return false;
        if (raw.equals("asc")) return true;
        throw OpenAiVideoRefusedException.invalid("order", "order must be asc or desc");
    }
}
