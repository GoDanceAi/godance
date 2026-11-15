package ai.godance.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public final class EstimateDtos {

    private EstimateDtos() { }

    /**
     * The fields of an image or video generation request that decide its
     * price, with the same names. {@code size} is accepted so a client can
     * send its request as is; it does not change the price.
     */
    public record EstimateRequest(
            @NotBlank String model,
            @Min(1) Integer n,
            String size,
            @Min(1) Integer seconds,
            // How many input images the request would carry (an image edit). Left out, none.
            @Min(0) @JsonProperty("input_images") Integer inputImages
    ) { }

    /**
     * What the matching generation request would pre-debit. {@code n} and
     * {@code seconds} are resolved as the job would resolve them; {@code seconds}
     * is null for an image model.
     */
    public record EstimateResponse(
            String model,
            String modality,
            int n,
            Integer seconds,
            @JsonProperty("estimated_cost_cents") long estimatedCostCents,
            String currency,
            // Echoed only when the request named it, so an estimate without it reads as it always has.
            @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("input_images") Integer inputImages
    ) {
        /** An estimate for a request that named no input images. */
        public EstimateResponse(String model, String modality, int n, Integer seconds,
                                long estimatedCostCents, String currency) {
            this(model, modality, n, seconds, estimatedCostCents, currency, null);
        }
    }
}
