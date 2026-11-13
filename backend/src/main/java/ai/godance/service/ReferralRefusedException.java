package ai.godance.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * A refused referral claim: problem+json whose {@code code} extension
 * member says which kind, so a client can branch without reading
 * {@code detail}.
 */
public final class ReferralRefusedException extends ResponseStatusException {

    /** No such code, or its owner can no longer invite anyone. 404. */
    public static final String CODE_UNKNOWN = "referral_code_unknown";
    /** The code exists but this account may not use it. 409. */
    public static final String CODE_NOT_ELIGIBLE = "referral_not_eligible";

    private ReferralRefusedException(HttpStatus status, String detail, String code) {
        super(status, detail);
        getBody().setProperty("code", code);
    }

    static ReferralRefusedException unknownCode() {
        return new ReferralRefusedException(HttpStatus.NOT_FOUND,
                "That invite code isn't valid.", CODE_UNKNOWN);
    }

    static ReferralRefusedException notEligible(String detail) {
        return new ReferralRefusedException(HttpStatus.CONFLICT, detail, CODE_NOT_ELIGIBLE);
    }
}
