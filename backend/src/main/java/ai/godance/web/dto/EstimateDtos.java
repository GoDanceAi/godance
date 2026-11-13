package ai.godance.web.dto;

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
            @Min(1) Integer seconds
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
            String currency
    ) { }
}
