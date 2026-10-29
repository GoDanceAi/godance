package ai.godance.web.dto;

import java.time.Instant;

import ai.godance.service.ParentalNotice;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** The parent-facing consent page's API ({@code /v1/auth/parental-consent}). */
public final class ParentalConsentDtos {

    private ParentalConsentDtos() { }

    /**
     * What the consent page shows for an emailed token: whose account it
     * is (first name and a masked email, no more), the direct notice, and
     * the bounds of the approving top-up, in cents.
     */
    public record ParentalConsentView(
            String childFirstName,
            String childEmailMasked,
            long minTopupCents,
            long maxTopupCents,
            String currency,
            Instant expiresAt,
            ParentalNotice notice
    ) { }

    /** Start the approving Checkout Session; the emailed token is the proof. */
    public record ParentalConsentCheckoutRequest(
            @NotBlank @Size(max = 64) String token,
            @Positive long amountCents
    ) { }
}
