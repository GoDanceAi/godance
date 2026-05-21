package ai.godance.service;

import ai.godance.domain.CreditSource;
import ai.godance.domain.User;

/**
 * Internal seam over a hosted-crypto top-up provider. A "rail" mints a
 * hosted invoice for a fiat-denominated top-up; the user pays in crypto;
 * the provider later delivers a signed webhook that credits the ledger.
 *
 * <p>v1 ships one live implementation, {@link NowPaymentsService}.
 * {@link BinancePayService} implements the same contract as a flagged-off
 * v1.1 scaffold so the second rail is a drop-in. Webhook verification is
 * provider-specific (different signature schemes / payload shapes) and so
 * lives on the concrete service, not this interface.
 */
public interface CryptoPaymentRail {

    /** Ledger {@link CreditSource} credits from this rail are attributed to. */
    CreditSource creditSource();

    /**
     * Whether this rail is live. Flagged-off rails (e.g. Binance Pay until
     * merchant KYC clears) return {@code false}; callers must not invoke
     * {@link #createInvoice} on a disabled rail.
     */
    boolean isEnabled();

    /**
     * Mint a hosted invoice for a {@code amountCents} fiat top-up. Returns
     * the URL the user is redirected to and an upstream reference id.
     *
     * @throws UnsupportedOperationException if the rail is not yet live
     */
    CryptoInvoice createInvoice(User user, long amountCents);

    /**
     * Hosted invoice handle. {@code reference} is the provider's invoice /
     * payment id (surfaced to the client for tracking); {@code hostedUrl}
     * is where the browser is redirected to complete payment.
     */
    record CryptoInvoice(String reference, String hostedUrl, long amountCents) { }
}
