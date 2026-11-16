package ai.godance.service;

import java.util.UUID;

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

    /**
     * What a request will hold: the model, the resolved count and length, and
     * the price. {@code byok}: the request goes out on the caller's own key
     * (round 14), so the price is Godance's fee on the list price.
     */
    public record Quote(ModelInfo model, int n, Integer seconds, long costCents, boolean byok) {
        public Quote(ModelInfo model, int n, Integer seconds, long costCents) {
            this(model, n, seconds, costCents, false);
        }
    }

    private final ModelCatalog catalog;
    private final CostCalculator calculator;
    private final VideoProviderGateway videoGateway;
    private final ProviderCredentialService byok;

    public GenerationQuotes(ModelCatalog catalog, CostCalculator calculator, VideoProviderGateway videoGateway,
                            ProviderCredentialService byok) {
        this.catalog = catalog;
        this.calculator = calculator;
        this.videoGateway = videoGateway;
        this.byok = byok;
    }

    /**
     * {@link #image(String, Integer, int)} for this caller: on their own key
     * (round 14) the price is Godance's fee on the list price, still with
     * the 1-cent minimum. A null caller is quoted the platform price.
     */
    public Quote image(UUID userId, String modelName, Integer n, int inputImages) {
        return onCallerKey(userId, image(modelName, n, inputImages), inputImages);
    }

    /** {@link #any(String, Integer, Integer, Integer)} for this caller, as {@link #image(UUID, String, Integer, int)} prices it. */
    public Quote any(UUID userId, String modelName, Integer n, Integer seconds, Integer inputImages) {
        Quote quote = any(modelName, n, seconds, inputImages);
        return quote.model().modality() == Modality.IMAGE
                ? onCallerKey(userId, quote, inputImages == null ? 0 : inputImages)
                : quote;
    }

    private Quote onCallerKey(UUID userId, Quote platform, int inputImages) {
        if (!byok.applies(userId, platform.model())) return platform;
        long fee = CostCalculator.feeMicrocents(
                calculator.imageListMicrocents(platform.model(), platform.n(), inputImages), byok.feeBasisPoints());
        return new Quote(platform.model(), platform.n(), platform.seconds(), CostCalculator.feeHoldCents(fee), true);
    }

    /** Godance's fee, in microcents, on {@code images} images made from {@code inputImages} on the caller's own key. */
    public long imageFeeMicrocents(ModelInfo model, int images, int inputImages) {
        return CostCalculator.feeMicrocents(calculator.imageListMicrocents(model, images, inputImages),
                byok.feeBasisPoints());
    }

    /** An image request's quote. 404 for an unknown model, 400 for a non-image model or too large an {@code n}. */
    public Quote image(String modelName, Integer n) {
        return image(modelName, n, 0);
    }

    /**
     * The quote of an image request that works from {@code inputImages} input
     * images (an edit): what {@link #image(String, Integer)} returns, plus the
     * model's price for each input image. A mask is not counted.
     */
    public Quote image(String modelName, Integer n, int inputImages) {
        ModelInfo model = require(modelName);
        if (model.modality() != Modality.IMAGE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "model " + modelName + " is not an image-generation model");
        }
        return imageQuote(model, n, inputImages);
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
        return any(modelName, n, seconds, null);
    }

    /**
     * {@link #any(String, Integer, Integer)} for a request that works from
     * input images. {@code inputImages} is how many; null is none. It prices
     * an image model's edit; a video model charges nothing for its inputs, so
     * it is ignored there.
     */
    public Quote any(String modelName, Integer n, Integer seconds, Integer inputImages) {
        if (inputImages != null && inputImages < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "input_images must be 0 or more");
        }
        ModelInfo model = require(modelName);
        return switch (model.modality()) {
            case IMAGE -> imageQuote(model, n, inputImages == null ? 0 : inputImages);
            case VIDEO -> videoQuote(model, n, seconds);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "model " + modelName + " is a " + model.modality()
                            + " model; estimates cover image and video models");
        };
    }

    /** The price of {@code images} images, with the 1-cent minimum. */
    public long imagePriceCents(ModelInfo model, int images) {
        return imagePriceCents(model, images, 0);
    }

    /** The price of {@code images} images made from {@code inputImages} input images, with the 1-cent minimum. */
    public long imagePriceCents(ModelInfo model, int images, int inputImages) {
        return Math.max(1L, calculator.imageCostCents(model, images, inputImages));
    }

    /** The price of {@code clips} clips of {@code seconds} seconds each, with the 1-cent minimum. */
    public long videoPriceCents(ModelInfo model, int seconds, int clips) {
        return Math.max(1L, calculator.videoCostCents(model, seconds, clips));
    }

    private Quote imageQuote(ModelInfo model, Integer n, int inputImages) {
        int count = count(model, n);
        return new Quote(model, count, null, imagePriceCents(model, count, inputImages));
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
