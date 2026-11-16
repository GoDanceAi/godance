package ai.godance.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.domain.Provider;

/**
 * A refused bring-your-own-key request (round 14 brief 03): problem+json
 * whose {@code code} extension member says which kind. None of these ever
 * carries the key, or what the provider said about it.
 */
public final class ByokRefusedException extends ResponseStatusException {

    /** No key may be stored for this provider ({@code godance.byok.providers}). 400. */
    public static final String CODE_NOT_SUPPORTED = "byok_provider_not_supported";
    /** The key is blank, too long, or not one token. 400. */
    public static final String CODE_INVALID_KEY = "invalid_api_key";
    /** The provider answered 401 or 403 to the key check. 400; nothing stored. */
    public static final String CODE_KEY_REJECTED = "byok_key_rejected";
    /** The provider gave no answer to the key check. 502; nothing stored. */
    public static final String CODE_CHECK_FAILED = "byok_check_failed";
    /** No key is stored for this provider. 404. */
    public static final String CODE_NOT_FOUND = "byok_credential_not_found";
    /**
     * A request on the caller's key that the provider refused (401 or 403),
     * when the caller did not allow the platform's key instead. 402; the
     * request's hold is refunded.
     */
    public static final String CODE_CREDENTIAL_REJECTED = "byok_credential_rejected";

    private ByokRefusedException(HttpStatus status, String code, String detail) {
        super(status, detail);
        getBody().setProperty("code", code);
    }

    static ByokRefusedException notSupported(String provider) {
        return new ByokRefusedException(HttpStatus.BAD_REQUEST, CODE_NOT_SUPPORTED,
                "keys cannot be stored for provider " + (provider == null ? "(none)" : provider));
    }

    static ByokRefusedException invalidKey(String detail) {
        return new ByokRefusedException(HttpStatus.BAD_REQUEST, CODE_INVALID_KEY, detail);
    }

    static ByokRefusedException keyRejected(Provider provider) {
        return new ByokRefusedException(HttpStatus.BAD_REQUEST, CODE_KEY_REJECTED,
                provider + " did not accept the key; nothing was stored");
    }

    static ByokRefusedException checkFailed(Provider provider) {
        return new ByokRefusedException(HttpStatus.BAD_GATEWAY, CODE_CHECK_FAILED,
                provider + " could not be reached to check the key; nothing was stored. Try again");
    }

    static ByokRefusedException notFound(Provider provider) {
        return new ByokRefusedException(HttpStatus.NOT_FOUND, CODE_NOT_FOUND, "no key is stored for " + provider);
    }

    /** The provider refused the caller's own key mid-request, and the platform's may not stand in. */
    public static ByokRefusedException credentialRejected(Provider provider) {
        return new ByokRefusedException(HttpStatus.PAYMENT_REQUIRED, CODE_CREDENTIAL_REJECTED,
                provider + " refused your key; replace it in Settings, or allow Godance's key as a fallback");
    }
}
