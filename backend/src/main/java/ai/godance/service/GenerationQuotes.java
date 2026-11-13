package ai.godance.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.domain.Modality;
import ai.godance.gateway.CostCalculator;
import ai.godance.gateway.ModelCatalog;
import ai.godance.gateway.ModelCatalog.ModelInfo;
import ai.godance.gateway.VideoProviderGateway;

/**
 * The price of an image or video request, before it runs. The one place the
 * pre-debit is worked out: both generation services hold exactly this, and
 * {@code POST /v1/estimates} returns it.
 *
 * <p>A quote resolves what the request leaves out the same way the job will:
 * {@code n} defaults to 1, and a video's length to its adapter's default,
 * which is also the length sent to the provider. It refuses, with a 400 and
 * before anything is charged, an {@code n} above what the model can return
 * ({@code max_n} in its capabilities; 1 when the model states none). Every
 * price has a 1-cent minimum.
 */
@Component
public class GenerationQuotes {

    /** What a request will hold: the model, the resolved count and length, and the price. */
    public record Quote(ModelInfo model, int n, Integer seconds, long costCents) { }

    private final ModelCatalog catalog;
    private final CostCalculator calculator;
    private final VideoProviderGateway videoGateway;

    public GenerationQuotes(ModelCatalog catalog, CostCalculator calculator, VideoProviderGateway videoGateway) {
        this.catalog = catalog;
        this.calculator = calculator;
        this.videoGateway = videoGateway;
    }

    /** An image request's quote. 404 for an unknown model, 400 for a non-image model or too large an {@code n}. */
    public Quote image(String modelName, Integer n) {
        ModelInfo model = require(modelName);
        if (model.modality() != Modality.IMAGE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "model " + modelName + " is not an image-generation model");
        }
        return imageQuote(model, n);
    }

    /** A video request's quote. 404 for an unknown model, 400 for a non-video model or too large an {@code n}. */
    public Quote video(String modelName, Integer n, Integer seconds) {
        ModelInfo model = require(modelName);
        if (model.modality() != Modality.VIDEO) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "model " + modelName + " is not a video model");
        }
        return videoQuote(model, n, seconds);
    }

    /** A quote for any image or video model; {@code seconds} is ignored for an image model. 400 for any other. */
    public Quote any(String modelName, Integer n, Integer seconds) {
        ModelInfo model = require(modelName);
        return switch (model.modality()) {
            case IMAGE -> imageQuote(model, n);
            case VIDEO -> videoQuote(model, n, seconds);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "model " + modelName + " is a " + model.modality()
                            + " model; estimates cover image and video models");
        };
    }

    /** The price of {@code images} images, with the 1-cent minimum. */
    public long imagePriceCents(ModelInfo model, int images) {
        return Math.max(1L, calculator.imageCostCents(model, images));
    }

    /** The price of {@code clips} clips of {@code seconds} seconds each, with the 1-cent minimum. */
    public long videoPriceCents(ModelInfo model, int seconds, int clips) {
        return Math.max(1L, calculator.videoCostCents(model, seconds, clips));
    }

    private Quote imageQuote(ModelInfo model, Integer n) {
        int count = count(model, n);
        return new Quote(model, count, null, imagePriceCents(model, count));
    }

    private Quote videoQuote(ModelInfo model, Integer n, Integer seconds) {
        int count = count(model, n);
        if (seconds != null && seconds < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "seconds must be at least 1");
        }
        int duration = seconds != null ? seconds : videoGateway.defaultDurationSeconds(model);
        return new Quote(model, count, duration, videoPriceCents(model, duration, count));
    }

    private int count(ModelInfo model, Integer n) {
        int requested = n == null ? 1 : n;
        if (requested < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "n must be at least 1");
        }
        int max = catalog.metadata(model.modelName()).capabilities().maxNOrDefault();
        if (requested > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "model " + model.modelName() + " returns at most " + max
                            + (max == 1 ? " output" : " outputs") + " per request; n was " + requested);
        }
        return requested;
    }

    private ModelInfo require(String modelName) {
        return catalog.find(modelName).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown model: " + modelName));
    }
}
