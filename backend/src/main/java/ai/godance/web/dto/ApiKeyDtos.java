package ai.godance.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import ai.godance.domain.ApiKey;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class ApiKeyDtos {

    private ApiKeyDtos() { }

    /**
     * {@code image_response} is optional: {@code "async"} or {@code "sync"}.
     * Left out, the key gets the server's default
     * ({@code godance.images.new-key-response}).
     */
    public record CreateApiKeyRequest(
            @NotBlank @Size(max = 255) String name,
            List<String> scopes,
            @JsonProperty("image_response") String imageResponse
    ) { }

    /** {@code PATCH /v1/keys/{id}}: the one setting of a key that can change. */
    public record UpdateApiKeyRequest(
            @JsonProperty("image_response") String imageResponse
    ) { }

    /** Returned once at creation. The {@code plaintext} field is not persisted. */
    public record CreateApiKeyResponse(
            UUID id,
            String name,
            String prefix,
            String plaintext,
            Instant createdAt,
            @JsonProperty("image_response") String imageResponse
    ) { }

    /**
     * Listing shape — never includes the plaintext key. {@code image_response}
     * is how image requests made with the key answer: {@code "async"} (a job
     * to poll) or {@code "sync"} (the images, once the job is done).
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
            @JsonProperty("image_response") String imageResponse
    ) {
        public static ApiKeySummary from(ApiKey k) {
            return new ApiKeySummary(
                    k.getId(),
                    k.getName(),
                    k.getKeyPrefix(),
                    k.getScopes(),
                    k.isActive(),
                    k.getCreatedAt(),
                    k.getLastUsedAt(),
                    k.getExpiresAt(),
                    k.getImageResponse().wire());
        }
    }
}
