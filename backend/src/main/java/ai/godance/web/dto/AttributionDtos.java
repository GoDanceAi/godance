package ai.godance.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** {@code /v1/attribution/**}, as the round-10 contract has it. */
public final class AttributionDtos {

    private AttributionDtos() { }

    /**
     * {@code POST /v1/attribution/first-touch}: what brought a sign-up to
     * the app. Every field is optional; at least one must be present, and
     * each is at most 100 printable characters.
     */
    public record FirstTouchRequest(
            @JsonProperty("utm_source")     String utmSource,
            @JsonProperty("utm_medium")     String utmMedium,
            @JsonProperty("utm_campaign")   String utmCampaign,
            @JsonProperty("gallery_recipe") String galleryRecipe,
            @JsonProperty("landing_path")   String landingPath,
            @JsonProperty("referrer_host")  String referrerHost
    ) { }
}
