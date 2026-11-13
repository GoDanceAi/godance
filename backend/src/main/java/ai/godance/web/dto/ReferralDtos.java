package ai.godance.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** {@code /v1/referrals/**}, as the round-9 contract has them. */
public final class ReferralDtos {

    private ReferralDtos() { }

    /** {@code GET /v1/referrals/me} while the feature is off: every other field absent. */
    public record DisabledView(boolean enabled) { }

    /**
     * {@code GET /v1/referrals/me} while it is on. Counts only: nothing
     * about who signed up with the code.
     */
    public record EnabledView(
            boolean enabled,
            String code,
            @JsonProperty("referrer_reward_cents") long referrerRewardCents,
            @JsonProperty("referee_reward_cents")  long refereeRewardCents,
            @JsonProperty("min_topup_cents")       long minTopupCents,
            long invited,
            long rewarded,
            @JsonProperty("earned_cents")          long earnedCents,
            // Present as null when the caller was not invited.
            @JsonInclude(JsonInclude.Include.ALWAYS)
            @JsonProperty("pending_reward_cents")  Long pendingRewardCents
    ) { }

    public record ClaimRequest(String code) { }

    public record ClaimResponse(
            @JsonProperty("referee_reward_cents") long refereeRewardCents,
            @JsonProperty("min_topup_cents")      long minTopupCents
    ) { }
}
