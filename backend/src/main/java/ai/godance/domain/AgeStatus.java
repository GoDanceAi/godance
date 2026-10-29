package ai.godance.domain;

/**
 * Whether an account needs a parent's approval. Signup asks for a birth
 * month; anyone under {@link ai.godance.service.ParentalConsentService#CONSENT_AGE}
 * starts {@link #CONSENT_PENDING}, and the JWT then carries the
 * {@code PENDING_CONSENT} scope instead of the role, which admits almost
 * nothing (see {@code SecurityConfig}).
 */
public enum AgeStatus {
    /** 16 or older, or never asked: accepting the Terms attests the age. */
    ADULT,
    /** Under 16 and no parent has approved yet: the account is restricted. */
    CONSENT_PENDING,
    /** Under 16, and a parent approved by paying a top-up on the account. */
    CONSENT_GRANTED
}
