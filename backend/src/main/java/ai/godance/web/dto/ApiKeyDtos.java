package ai.godance.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import ai.godance.domain.ApiKey;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class ApiKeyDtos {

    private ApiKeyDtos() { }

    public record CreateApiKeyRequest(
            @NotBlank @Size(max = 255) String name,
            List<String> scopes
    ) { }

    /** Returned once at creation. The {@code plaintext} field is not persisted. */
    public record CreateApiKeyResponse(
            UUID id,
            String name,
            String prefix,
            String plaintext,
            Instant createdAt
    ) { }

    /** Listing shape — never includes the plaintext key. */
    public record ApiKeySummary(
            UUID id,
            String name,
            String prefix,
            List<String> scopes,
            boolean active,
            Instant createdAt,
            Instant lastUsedAt,
            Instant expiresAt
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
                    k.getExpiresAt());
        }
    }
}
