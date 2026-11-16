package ai.godance.web.dto;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Bring your own key (round 14 brief 03): {@code /v1/provider-credentials}. Field names are snake_case. */
public final class ByokDtos {

    private ByokDtos() { }

    /**
     * {@code PUT /v1/provider-credentials/{provider}}. {@code api_key} is
     * checked with the provider and stored encrypted; it may be left out to
     * change only {@code fallback_to_platform} of a key already stored.
     */
    public record CredentialRequest(
            @JsonProperty("api_key") String apiKey,
            @JsonProperty("fallback_to_platform") Boolean fallbackToPlatform
    ) {
        /** Never the key. */
        @Override
        public String toString() {
            return "CredentialRequest[api_key=" + (apiKey == null ? "absent" : "…") + ", fallback_to_platform="
                    + fallbackToPlatform + "]";
        }
    }

    /**
     * One provider a key may be stored for, or one a key is stored for. Never
     * the key: {@code last4} is all of it that is shown.
     *
     * <p>{@code status}: {@code valid}, {@code invalid} (the provider refused
     * it), {@code unchecked}, {@code unsupported_adapter} (stored, but no
     * request to this provider can use it yet, so they go out on the
     * platform's key), {@code not_supported} (stored for a provider no longer
     * offered), or {@code none} (no key stored). {@code used_for} names what
     * the key is used for: {@code chat} and {@code images}.
     * {@code fee_basis_points} is Godance's fee on a request that uses it.
     */
    public record CredentialView(
            String provider,
            @JsonInclude(JsonInclude.Include.ALWAYS) String last4,
            String status,
            @JsonProperty("fallback_to_platform") boolean fallbackToPlatform,
            @JsonProperty("used_for") List<String> usedFor,
            @JsonInclude(JsonInclude.Include.ALWAYS) @JsonProperty("checked_at") Instant checkedAt,
            @JsonProperty("fee_basis_points") int feeBasisPoints
    ) { }
}
