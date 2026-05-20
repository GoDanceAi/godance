package ai.godance.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public final class BillingDtos {

    private BillingDtos() { }

    /**
     * One-shot top-up. {@code saveCard=true} captures the card for off-session
     * future use (required before the user can enable auto-recharge); defaults
     * to false so the existing one-tap top-up flow is unchanged.
     */
    public record TopUpRequest(
            @Positive long amountCents,
            @JsonProperty("save_card") Boolean saveCard
    ) {
        public boolean saveCardOrFalse() { return saveCard != null && saveCard; }
    }

    public record TopUpResponse(String sessionId, String checkoutUrl, long amountCents) { }

    // ----- Auto-recharge (Session 13) ---------------------------------

    public record AutoRechargeConfigRequest(
            boolean enabled,
            @PositiveOrZero @JsonProperty("threshold_cents") Long thresholdCents,
            @PositiveOrZero @JsonProperty("topup_cents")     Long topupCents
    ) { }

    public record AutoRechargeConfigView(
            boolean enabled,
            @JsonProperty("threshold_cents")            Long thresholdCents,
            @JsonProperty("topup_cents")                Long topupCents,
            @JsonProperty("payment_method_on_file")     boolean paymentMethodOnFile,
            @JsonProperty("last_failure_at")            java.time.Instant lastFailureAt,
            @JsonProperty("last_failure_reason")        String lastFailureReason
    ) { }
}
