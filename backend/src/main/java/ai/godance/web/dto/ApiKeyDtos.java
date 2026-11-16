package ai.godance.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import ai.godance.domain.ApiKey;
import ai.godance.service.SpendingLimitService.KeySpend;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

public final class ApiKeyDtos {

    private ApiKeyDtos() { }

    /**
     * {@code image_response} is optional: {@code "async"} or {@code "sync"}.
     * Left out, the key gets the server's default
     * ({@code godance.images.new-key-response}).
     *
     * <p>{@code daily_cap_cents} and {@code monthly_cap_cents} are the most
     * the key may spend in a UTC day and a UTC month: a whole number of
     * cents, at least 1, or null (or left out) for no cap. {@code expires_at}
     * is when the key stops working: an ISO-8601 date and time in the
     * future, or null (or left out) for never. They are read as JSON nodes
     * so that {@code ApiKeyService} checks them with the same rules, and the
     * same errors, as {@code PATCH /v1/keys/{id}}.
     */
    public record CreateApiKeyRequest(
            @NotBlank @Size(max = 255) String name,
            List<String> scopes,
            @JsonProperty("image_response") String imageResponse,
            @JsonProperty("daily_cap_cents") JsonNode dailyCapCents,
            @JsonProperty("monthly_cap_cents") JsonNode monthlyCapCents,
            @JsonProperty("expires_at") JsonNode expiresAt
    ) { }

    /**
     * {@code PATCH /v1/keys/{id}}: the settings of a key that can change.
     * Each field is optional and at least one must be sent. A field left out
     * is left as it is ({@code null} here); a cap or the expiry sent as JSON
     * {@code null} is cleared (a null node here), which is why these are
     * nodes and not values.
     */
    public record UpdateApiKeyRequest(
            @JsonProperty("image_response") JsonNode imageResponse,
            @JsonProperty("daily_cap_cents") JsonNode dailyCapCents,
            @JsonProperty("monthly_cap_cents") JsonNode monthlyCapCents,
            @JsonProperty("expires_at") JsonNode expiresAt
    ) { }

    /**
     * Returned once at creation. The {@code plaintext} field is not
     * persisted. The caps and the expiry are what the key was created with
     * (null: none).
     */
    public record CreateApiKeyResponse(
            UUID id,
            String name,
            String prefix,
            String plaintext,
            Instant createdAt,
            @JsonProperty("image_response") String imageResponse,
            @JsonProperty("daily_cap_cents") Long dailyCapCents,
            @JsonProperty("monthly_cap_cents") Long monthlyCapCents,
            @JsonProperty("expires_at") Instant expiresAt
    ) { }

    /**
     * Listing shape — never includes the plaintext key. {@code image_response}
     * is how image requests made with the key answer: {@code "async"} (a job
     * to poll) or {@code "sync"} (the images, once the job is done).
     *
     * <p>{@code daily_cap_cents} and {@code monthly_cap_cents} are the key's
     * caps (null: none), and {@code spent_today_cents} and
     * {@code spent_month_cents} what it has spent in the current UTC day and
     * month, as {@code SpendingLimitService} counts it when it enforces
     * them. The expiry is sent under both {@code expires_at}, the name the
     * requests use, and {@code expiresAt}, which clients read since before
     * the caps.
     */
    public record ApiKeySummary(
            UUID id,
            String name,
            String prefix,
            List<String> scopes,
            boolean active,
            Instant createdAt,
            Instant lastUsedAt,
            Instant expiresAt,
            @JsonProperty("image_response") String imageResponse,
            @JsonProperty("daily_cap_cents") Long dailyCapCents,
            @JsonProperty("monthly_cap_cents") Long monthlyCapCents,
            @JsonProperty("spent_today_cents") long spentTodayCents,
            @JsonProperty("spent_month_cents") long spentMonthCents
    ) {
        public static ApiKeySummary from(ApiKey k, KeySpend spend) {
            return new ApiKeySummary(
                    k.getId(),
                    k.getName(),
                    k.getKeyPrefix(),
                    k.getScopes(),
                    k.isActive(),
                    k.getCreatedAt(),
                    k.getLastUsedAt(),
                    k.getExpiresAt(),
                    k.getImageResponse().wire(),
                    k.getDailyCreditCapCents(),
                    k.getMonthlyCreditCapCents(),
                    spend.todayCents(),
                    spend.monthCents());
        }

        /** The expiry again, under the name the requests use. */
        @JsonProperty("expires_at")
        public Instant expiresAtSnakeCase() {
            return expiresAt;
        }
    }
}
