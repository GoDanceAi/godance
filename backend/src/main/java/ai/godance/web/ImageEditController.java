package ai.godance.web;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FormFieldPart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import ai.godance.domain.Modality;
import ai.godance.gateway.ImageDtos.ImageRequest;
import ai.godance.security.JwtService;
import ai.godance.service.ImageEdits;
import ai.godance.service.ImageGenerationService;
import ai.godance.service.ImageGenerationService.ImageResult;
import ai.godance.service.ImageResponseFormat;
import ai.godance.service.InputService.ResolvedInput;
import ai.godance.service.JobOrigin;
import ai.godance.service.RecipeRunService;
import ai.godance.service.RecipeRunService.RunPrompt;
import ai.godance.web.dto.ImageEditDtos.ImageEditRequest;
import ai.godance.web.dto.InputDtos.InputRef;
import ai.godance.web.dto.RecipeDtos.RecipeRef;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code POST /v1/images/edits}: make an image from images. Two forms, one
 * job path.
 *
 * <ul>
 *   <li><b>Multipart</b>, OpenAI's form, which its SDK sends for
 *       {@code client.images.edit(…)}: {@code model}, {@code prompt}, one or
 *       more {@code image} parts (also as {@code image[]}), and optionally
 *       {@code mask}, {@code n}, {@code size}, {@code quality} and
 *       {@code response_format}. Each image and the mask goes through the
 *       upload checks of {@code POST /v1/files} and becomes an input file of
 *       the caller.</li>
 *   <li><b>JSON</b>, which names inputs that are already stored:
 *       {@code images} and {@code mask} as {@code {"file_id"}} or
 *       {@code {"job_output"}}.</li>
 * </ul>
 *
 * <p>From there an edit is a generation: the same pre-debit, lease,
 * settlement, webhook and refund, and the same answer by the key's
 * {@code image_response} ({@link ImageJobAnswers}). {@code gallery_recipe}
 * and {@code recipe} are taken as on generations.
 *
 * <p>While inputs are switched off the route answers {@code inputs_disabled}
 * before it reads a part or looks anything up, and writes nothing.
 */
@RestController
@RequestMapping("/v1")
public class ImageEditController {

    /** The fields both forms share, as text or already parsed. */
    private record Fields(String model, String prompt, Integer n, String size, String quality,
                          String responseFormat, Boolean async, String webhookUrl, String galleryRecipe,
                          RecipeRef recipe) { }

    private final ImageGenerationService service;
    private final ImageEdits edits;
    private final RecipeRunService recipes;
    private final ImageJobAnswers answers;
    private final InputUploadIntake intake;
    private final ObjectMapper json;

    public ImageEditController(ImageGenerationService service, ImageEdits edits, RecipeRunService recipes,
                               ImageJobAnswers answers, InputUploadIntake intake, ObjectMapper json) {
        this.service = service;
        this.edits = edits;
        this.recipes = recipes;
        this.answers = answers;
        this.intake = intake;
        this.json = json;
    }

    @PostMapping(value = "/images/edits", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<?>> editJson(
            @RequestBody ImageEditRequest req,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(name = "Prefer", required = false) String prefer) {
        edits.requireEnabled();
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        Fields fields = new Fields(req.model(), req.prompt(), req.n(), req.size(), req.quality(),
                req.responseFormat(), req.async(), req.webhookUrl(), req.galleryRecipe(), req.recipe());
        return run(userId, apiKeyId(jwt), fields, new ImageEdits.Refs(req.images(), req.mask()), prefer);
    }

    @PostMapping(value = "/images/edits", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<?>> editMultipart(
            @AuthenticationPrincipal Jwt jwt,
            ServerWebExchange exchange,
            @RequestHeader(name = "Prefer", required = false) String prefer) {
        edits.requireEnabled();
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        UUID apiKeyId = apiKeyId(jwt);
        String ip = InputUploadIntake.clientIp(exchange);

        // Whether the account may use inputs at all is settled before a byte of the form is read.
        return Mono.fromRunnable(() -> edits.requireMayUse(userId))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.defer(exchange::getMultipartData))
                .flatMap(form -> {
                    Fields fields = fieldsOf(form);
                    List<Part> images = new ArrayList<>(parts(form, "image"));
                    images.addAll(parts(form, "image[]"));
                    Part mask = form.getFirst("mask");
                    // Everything that can refuse the request without its images does so first,
                    // so a request that cannot run leaves no stored file behind.
                    requireWellFormed(fields);
                    return Mono.fromRunnable(() -> edits.requireSupported(fields.model(), images.size(), mask != null))
                            .subscribeOn(Schedulers.boundedElastic())
                            .then(storeAll(userId, ip, images))
                            .flatMap(stored -> mask == null
                                    ? Mono.just(new ImageEdits.Refs(stored, null))
                                    : store(userId, ip, mask).map(ref -> new ImageEdits.Refs(stored, ref)))
                            .flatMap(refs -> run(userId, apiKeyId, fields, refs, prefer));
                })
                .onErrorMap(InputUploads::isTooLarge, e -> intake.tooLarge(userId, ip));
    }

    /** Stores the images one after another, in the order given, each admitted as an upload of its own. */
    private Mono<List<InputRef>> storeAll(UUID userId, String ip, List<Part> images) {
        return Flux.fromIterable(images).concatMap(image -> store(userId, ip, image)).collectList();
    }

    private Mono<InputRef> store(UUID userId, String ip, Part part) {
        return intake.admit(userId, ip)
                .then(Mono.defer(() -> intake.store(userId, ip, part)))
                .map(file -> InputRef.file(file.getId()));
    }

    /**
     * The edit, once its inputs are named: the checks in order, then the job.
     * A recipe is rendered, and every input checked and loaded, before
     * anything is priced or charged.
     */
    private Mono<ResponseEntity<?>> run(UUID userId, UUID apiKeyId, Fields fields, ImageEdits.Refs refs, String prefer) {
        requireWellFormed(fields);
        // The channel comes from the credentials; a malformed gallery_recipe is a 400 before any charge.
        JobOrigin origin = JobOrigin.of(apiKeyId, fields.galleryRecipe());
        ImageResponseFormat format = ImageResponseFormat.parse(fields.responseFormat());   // 400 before any charge
        ImageRequest domain = new ImageRequest(fields.model(), fields.prompt(), fields.size(), fields.n(),
                null, fields.quality());
        boolean runsAsJob = service.requiresAsyncFor(domain);
        boolean asJob = Boolean.TRUE.equals(fields.async()) || runsAsJob;
        boolean mayWait = answers.mayWait(apiKeyId, runsAsJob, fields.async(), prefer);

        return Mono.fromCallable(() -> {
                    List<ResolvedInput> inputs = edits.resolve(userId, fields.model(), refs);
                    RunPrompt run = recipes.resolve(userId, Modality.IMAGE, fields.prompt(), fields.recipe());
                    ImageRequest request = edits.attach(domain.withPrompt(run.prompt()), inputs);
                    JobOrigin from = run.origin(origin);
                    return asJob
                            ? (Object) service.executeAsync(userId, apiKeyId, request, fields.webhookUrl(), from, inputs)
                            : service.execute(userId, apiKeyId, request, from, inputs);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(outcome -> outcome instanceof UUID jobId
                        ? answers.forJob(apiKeyId, jobId, format, mayWait)
                        : Mono.just(answers.forResult((ImageResult) outcome, format, domain.model())));
    }

    private static void requireWellFormed(Fields fields) {
        if (fields.model() == null || fields.model().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "model must not be blank");
        }
        if (fields.n() != null && fields.n() < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "n must be at least 1");
        }
    }

    // ===== The multipart form =========================================

    private Fields fieldsOf(MultiValueMap<String, Part> form) {
        return new Fields(text(form, "model"), text(form, "prompt"), integer(form, "n"), text(form, "size"),
                text(form, "quality"), text(form, "response_format"), bool(form, "async"),
                text(form, "webhook_url"), text(form, "gallery_recipe"), recipe(form));
    }

    private static List<Part> parts(MultiValueMap<String, Part> form, String name) {
        List<Part> found = form.get(name);
        return found == null ? List.of() : found;
    }

    /** A text field's value, or null when the form has no such field or it is empty. */
    private static String text(MultiValueMap<String, Part> form, String name) {
        Part part = form.getFirst(name);
        if (part == null) return null;
        if (!(part instanceof FormFieldPart field)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " must be a text field");
        }
        String value = field.value();
        return value == null || value.isBlank() ? null : value;
    }

    private static Integer integer(MultiValueMap<String, Part> form, String name) {
        String value = text(form, name);
        if (value == null) return null;
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " must be a whole number");
        }
    }

    private static Boolean bool(MultiValueMap<String, Part> form, String name) {
        String value = text(form, name);
        return value == null ? null : Boolean.valueOf(value.trim());
    }

    /** {@code recipe} in a form is the JSON object a JSON request would carry, as text. */
    private RecipeRef recipe(MultiValueMap<String, Part> form) {
        String value = text(form, "recipe");
        if (value == null) return null;
        try {
            return json.readValue(value, RecipeRef.class);
        } catch (JacksonException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "recipe must be a JSON object: {\"version_id\": …, \"variables\": {…}}");
        }
    }

    /** The API key id when the caller authenticated with an API key; null for a JWT session. */
    private static UUID apiKeyId(Jwt jwt) {
        String claim = jwt.getClaimAsString("api_key_id");
        return claim == null ? null : UUID.fromString(claim);
    }
}
