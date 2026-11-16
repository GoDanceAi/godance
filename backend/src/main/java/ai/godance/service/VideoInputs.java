package ai.godance.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.domain.InputRole;
import ai.godance.domain.Modality;
import ai.godance.gateway.ModelCapabilities;
import ai.godance.gateway.ModelCatalog;
import ai.godance.gateway.ModelCatalog.ModelInfo;
import ai.godance.gateway.VideoDtos.VideoInput;
import ai.godance.gateway.VideoDtos.VideoRequest;
import ai.godance.gateway.VideoProviderAdapter.InputRules;
import ai.godance.gateway.VideoProviderGateway;
import ai.godance.service.InputService.InputFacts;
import ai.godance.service.InputService.InputUse;
import ai.godance.service.InputService.ResolvedInput;
import ai.godance.web.dto.InputDtos.InputRef;

/**
 * The images a video request starts from, ends on or follows: checked
 * against the model and loaded, before anything is charged.
 *
 * <p>Three things decide whether an input is taken, in this order:
 * <ol>
 *   <li>inputs are switched on ({@code godance.inputs.enabled});</li>
 *   <li>the model's capabilities say it takes that input, and that many;</li>
 *   <li>brief 01's rules, in {@link InputService#resolve}: the account may
 *       use inputs, and each image is the caller's own.</li>
 * </ol>
 * Then each image is checked against what the model's provider documents:
 * its type, its size and its shape. Nothing is cropped or converted here.
 */
@Component
public class VideoInputs {

    /**
     * The input fields of a video request, as the caller wrote them. An
     * empty list of reference images is the same as none.
     */
    public record Refs(InputRef firstFrame, InputRef lastFrame, List<InputRef> referenceImages) {

        public static final Refs NONE = new Refs(null, null, List.of());

        public Refs {
            // A list from JSON may hold a null; it is refused later, as a reference that names nothing.
            referenceImages = referenceImages == null
                    ? List.of() : Collections.unmodifiableList(new ArrayList<>(referenceImages));
        }

        public boolean isEmpty() {
            return firstFrame == null && lastFrame == null && referenceImages.isEmpty();
        }
    }

    private final InputService inputs;
    private final ModelCatalog catalog;
    private final VideoProviderGateway gateway;

    public VideoInputs(InputService inputs, ModelCatalog catalog, VideoProviderGateway gateway) {
        this.inputs = inputs;
        this.catalog = catalog;
        this.gateway = gateway;
    }

    /**
     * {@code inputs_disabled} when the request names an input and inputs are
     * switched off. Called before the model is even looked up, so that with
     * inputs off the new fields answer one thing only.
     */
    public void requireEnabledFor(Refs refs) {
        if (!refs.isEmpty()) inputs.requireEnabled();
    }

    /**
     * Refuses inputs the model cannot take, knowing only the model's name
     * and which inputs the request has; what each one points to is not
     * looked at. A multipart form ({@code POST /v1/videos} with an
     * {@code input_reference} file) calls this before it stores the image,
     * so a request that cannot run leaves no file. {@link #resolve} makes
     * the same checks again, with every other one, when the job is
     * submitted.
     *
     * @throws ResponseStatusException 403 {@code inputs_disabled}; 404 for an unknown model; 400 for a model
     *                                 that is not a video model, and {@code input_not_supported} for inputs it
     *                                 does not take
     */
    public void requireTakes(String modelName, Refs refs) {
        inputs.requireEnabled();
        ModelInfo model = catalog.find(modelName).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown model: " + modelName));
        if (model.modality() != Modality.VIDEO) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "model " + modelName + " is not a video model");
        }
        named(model, refs);
    }

    /** {@link InputService#requireMayUse}: switched on, an adult's account, the current terms accepted. */
    public void requireMayUse(UUID userId) {
        inputs.requireMayUse(userId);
    }

    /**
     * Checks the request's inputs against the model and loads them, in the
     * order they are recorded: the first frame, the last frame, then the
     * reference images as given. Writes nothing.
     *
     * @return the inputs to record with the job; empty for a request with none, with no checks run
     */
    public List<ResolvedInput> resolve(UUID userId, ModelInfo model, Refs refs) {
        if (refs.isEmpty()) return List.of();
        inputs.requireEnabled();
        Named named = named(model, refs);
        List<ResolvedInput> resolved = inputs.resolve(userId, named.uses());
        for (int i = 0; i < resolved.size(); i++) {
            ResolvedInput input = resolved.get(i);
            requireAccepted(model.modelName(), named.fields().get(i), input.contentType(),
                    (long) input.bytes().length, input.width(), input.height(), named.rules());
        }
        return resolved;
    }

    /**
     * {@link #resolve} without loading any image, for a request that will
     * run later (a row of a recipe batch): the same checks of the model, the
     * account and each input, against what is on record about the input.
     * An uploaded file's type, size and shape are on record; a job output's
     * shape is not, and is checked when the row's job starts.
     */
    public void check(UUID userId, ModelInfo model, Refs refs) {
        if (refs.isEmpty()) return;
        inputs.requireEnabled();
        Named named = named(model, refs);
        List<InputFacts> facts = inputs.inspect(userId, named.uses());
        for (int i = 0; i < facts.size(); i++) {
            InputFacts input = facts.get(i);
            requireAccepted(model.modelName(), named.fields().get(i), input.contentType(),
                    input.sizeBytes(), input.width(), input.height(), named.rules());
        }
    }

    /**
     * Refuses inputs the model cannot take, knowing only which fields the
     * request has and how many reference images: what each one points to is
     * not looked at. For a caller that knows the shape of a request before
     * it knows the inputs themselves (a recipe's slots).
     */
    public void requireSupported(ModelInfo model, Refs refs) {
        if (refs.isEmpty()) return;
        inputs.requireEnabled();
        named(model, refs);
    }

    /** A request's inputs, each with the field it came in, once the model is known to take them. */
    private record Named(List<InputUse> uses, List<String> fields, InputRules rules) { }

    /** Everything that can be checked from the model and the shape of the request alone. */
    private Named named(ModelInfo model, Refs refs) {
        String name = model.modelName();
        ModelCapabilities.Inputs takes = catalog.metadata(name).capabilities().inputs();
        InputRules rules = gateway.inputRules(model);
        // An adapter that sends no images takes none, whatever the catalog row says.
        boolean adapterSendsImages = !rules.contentTypes().isEmpty();

        if (refs.firstFrame() != null && !(adapterSendsImages && takes.firstFrame())) {
            throw VideoInputRefusedException.fieldNotSupported(name, "first_frame");
        }
        if (refs.lastFrame() != null && !(adapterSendsImages && takes.lastFrame())) {
            throw VideoInputRefusedException.fieldNotSupported(name, "last_frame");
        }
        int references = refs.referenceImages().size();
        if (references > 0 && !(adapterSendsImages && takes.referenceImages())) {
            throw VideoInputRefusedException.fieldNotSupported(name, "reference_images");
        }
        if (references > takes.maxReferenceImages()) {
            throw VideoInputRefusedException.tooManyReferenceImages(name, takes.maxReferenceImages(), references);
        }
        // Every provider that ends a clip on a frame needs the frame it starts from.
        if (refs.lastFrame() != null && refs.firstFrame() == null) {
            throw VideoInputRefusedException.combinationNotSupported(
                    "The model " + name + " takes last_frame only together with first_frame.", "last_frame");
        }
        if (references > 0 && (refs.firstFrame() != null || refs.lastFrame() != null) && !rules.framesWithReferences()) {
            throw VideoInputRefusedException.combinationNotSupported(
                    "The model " + name + " takes a first frame, with or without a last frame, or reference images, "
                            + "but not both in one request.", "reference_images");
        }

        List<InputUse> uses = new ArrayList<>(references + 2);
        List<String> fields = new ArrayList<>(references + 2);
        if (refs.firstFrame() != null) {
            uses.add(new InputUse(InputRole.FIRST_FRAME, refs.firstFrame()));
            fields.add("first_frame");
        }
        if (refs.lastFrame() != null) {
            uses.add(new InputUse(InputRole.LAST_FRAME, refs.lastFrame()));
            fields.add("last_frame");
        }
        for (int i = 0; i < references; i++) {
            uses.add(new InputUse(InputRole.REFERENCE_IMAGE, refs.referenceImages().get(i)));
            fields.add("reference_images[" + i + "]");
        }
        return new Named(uses, fields, rules);
    }

    /**
     * The image against what the provider documents: its type, its size in
     * bytes, its shape. A size or shape that is not known (null) is not
     * checked here.
     */
    private static void requireAccepted(String model, String field, String contentType, Long sizeBytes,
                                        Integer width, Integer height, InputRules rules) {
        if (!rules.contentTypes().contains(contentType)) {
            throw InputRefusedException.typeUnsupported(field + " is " + typeName(contentType)
                    + ". The model " + model + " takes " + typeNames(rules) + " images.");
        }
        if (sizeBytes != null && sizeBytes > rules.maxBytes()) {
            throw InputRefusedException.tooLarge(field + " is " + megabytes(sizeBytes)
                    + " MB. The model " + model + " takes images of at most " + megabytes(rules.maxBytes()) + " MB.");
        }
        if (width == null || height == null) return;
        double aspect = (double) width / height;
        if (aspect < rules.minAspect() || aspect > rules.maxAspect()) {
            throw VideoInputRefusedException.aspectMismatch(model, field, width, height,
                    rules.minAspect(), rules.maxAspect());
        }
    }

    /**
     * The request with its images, each in the place its role names.
     * Reference images keep their order.
     */
    public VideoRequest attach(VideoRequest request, List<ResolvedInput> resolved) {
        if (resolved.isEmpty()) return request;
        VideoInput first = null;
        VideoInput last = null;
        List<VideoInput> references = new ArrayList<>();
        for (ResolvedInput input : resolved) {
            VideoInput image = new VideoInput(input.bytes(), input.contentType());
            switch (input.role()) {
                case FIRST_FRAME -> first = image;
                case LAST_FRAME -> last = image;
                case REFERENCE_IMAGE -> references.add(image);
                default -> throw new IllegalStateException("a video takes no " + input.role().wire() + " input");
            }
        }
        return request.withInputs(first, last, references);
    }

    private static String typeName(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> "a JPEG";
            case "image/png" -> "a PNG";
            case "image/webp" -> "a WebP";
            default -> contentType;
        };
    }

    private static String typeNames(InputRules rules) {
        List<String> names = new ArrayList<>();
        for (String type : List.of("image/jpeg", "image/png", "image/webp")) {
            if (rules.contentTypes().contains(type)) names.add(type.substring("image/".length()).toUpperCase(Locale.ROOT));
        }
        if (names.size() == 1) return names.get(0);
        return String.join(", ", names.subList(0, names.size() - 1)) + " or " + names.get(names.size() - 1);
    }

    private static String megabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / (1024.0 * 1024.0));
    }
}
