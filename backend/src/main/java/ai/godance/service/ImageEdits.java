package ai.godance.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.domain.InputRole;
import ai.godance.domain.Modality;
import ai.godance.gateway.ImageDtos.ImageInput;
import ai.godance.gateway.ImageDtos.ImageRequest;
import ai.godance.gateway.ImageProviderGateway;
import ai.godance.gateway.ModelCapabilities;
import ai.godance.gateway.ModelCatalog;
import ai.godance.gateway.ModelCatalog.ModelInfo;
import ai.godance.service.InputService.InputUse;
import ai.godance.service.InputService.ResolvedInput;
import ai.godance.web.dto.InputDtos.InputRef;

/**
 * The input images and the mask of an image edit: checked against the model
 * and loaded, before anything is charged.
 *
 * <p>In order: inputs are switched on; the model is an image model whose
 * capabilities say it takes that many input images, and a mask if there is
 * one; then brief 01's rules, in {@link InputService#resolve}: the account
 * may use inputs, and each image is the caller's own.
 */
@Component
public class ImageEdits {

    /** The inputs of an edit as the caller named them: the images in order, and the mask or null. */
    public record Refs(List<InputRef> images, InputRef mask) {
        public Refs {
            // A list from JSON may hold a null; it is refused later, as a reference that names nothing.
            images = images == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(images));
        }
    }

    private final InputService inputs;
    private final ModelCatalog catalog;
    private final ImageProviderGateway gateway;

    public ImageEdits(InputService inputs, ModelCatalog catalog, ImageProviderGateway gateway) {
        this.inputs = inputs;
        this.catalog = catalog;
        this.gateway = gateway;
    }

    /** {@code inputs_disabled} while inputs are switched off: the route's first answer, before it reads anything. */
    public void requireEnabled() {
        inputs.requireEnabled();
    }

    /** {@link InputService#requireMayUse}: switched on, an adult's account, the current terms accepted. */
    public void requireMayUse(UUID userId) {
        inputs.requireMayUse(userId);
    }

    /**
     * Refuses an edit the model cannot take, knowing only how many images it
     * has and whether it has a mask. The multipart form calls this before it
     * stores a single part, so a request that cannot run leaves no file.
     *
     * @throws ResponseStatusException 404 for an unknown model; 400 for a model that is not an image model,
     *                                 for no image at all, and {@code input_not_supported} for what the model does not take
     */
    public ModelInfo requireSupported(String modelName, int images, boolean mask) {
        requireEnabled();
        ModelInfo model = catalog.find(modelName).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown model: " + modelName));
        if (model.modality() != Modality.IMAGE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "model " + modelName + " is not an image-generation model");
        }
        if (images < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "an edit needs at least one image");
        }
        ModelCapabilities.Inputs takes = catalog.metadata(modelName).capabilities().inputs();
        // An adapter that sends no images takes none, whatever the catalog row says.
        if (!takes.images() || !gateway.editsImages(model)) {
            throw ImageEditRefusedException.imagesNotSupported(modelName);
        }
        if (images > takes.maxImages()) {
            throw ImageEditRefusedException.tooManyImages(modelName, takes.maxImages(), images);
        }
        if (mask && !takes.mask()) {
            throw ImageEditRefusedException.maskNotSupported(modelName);
        }
        return model;
    }

    /**
     * Checks the edit's inputs against the model and loads them, in the order
     * they are recorded: the images as given, then the mask. Writes nothing.
     */
    public List<ResolvedInput> resolve(UUID userId, String modelName, Refs refs) {
        requireSupported(modelName, refs.images().size(), refs.mask() != null);
        return inputs.resolve(userId, uses(refs));
    }

    /**
     * {@link #resolve} without loading any image, for an edit that will run
     * later (a row of a recipe batch): the same checks of the model, the
     * account and each input. Writes nothing and reads nothing from storage.
     */
    public void check(UUID userId, String modelName, Refs refs) {
        requireSupported(modelName, refs.images().size(), refs.mask() != null);
        inputs.inspect(userId, uses(refs));
    }

    /** The edit's inputs in the order they are recorded: the images as given, then the mask. */
    private static List<InputUse> uses(Refs refs) {
        List<InputUse> uses = new ArrayList<>(refs.images().size() + 1);
        for (InputRef image : refs.images()) uses.add(new InputUse(InputRole.IMAGE, image));
        if (refs.mask() != null) uses.add(new InputUse(InputRole.MASK, refs.mask()));
        return uses;
    }

    /** The request with its input images and its mask, each with a short-lived signed address for a provider that needs one. */
    public ImageRequest attach(ImageRequest request, List<ResolvedInput> resolved) {
        List<ImageInput> images = new ArrayList<>();
        ImageInput mask = null;
        for (ResolvedInput input : resolved) {
            ImageInput image = new ImageInput(input.bytes(), input.contentType(), inputs.providerUrl(input));
            switch (input.role()) {
                case IMAGE -> images.add(image);
                case MASK -> mask = image;
                default -> throw new IllegalStateException("an image edit takes no " + input.role().wire() + " input");
            }
        }
        return request.withInputs(images, mask);
    }
}
