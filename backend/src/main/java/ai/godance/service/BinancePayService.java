package ai.godance.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import ai.godance.domain.CreditSource;
import ai.godance.domain.User;

/**
 * v1.1 Binance Pay rail — scaffold only. Implements {@link CryptoPaymentRail}
 * so the second crypto rail is a drop-in once it goes live, but ships
 * flagged off: Binance Pay requires merchant KYC onboarding (weeks of
 * lead time), so there is no live integration here yet.
 *
 * <p>{@link #createInvoice} throws {@link UnsupportedOperationException} —
 * callers must gate on {@link #isEnabled()} first.
 */
@Service
public class BinancePayService implements CryptoPaymentRail {

    private final boolean enabled;

    public BinancePayService(@Value("${godance.binancepay.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public CreditSource creditSource() {
        return CreditSource.BINANCE_PAY;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public CryptoInvoice createInvoice(User user, long amountCents) {
        throw new UnsupportedOperationException(
                "Binance Pay rail is not yet live (pending merchant KYC onboarding)");
    }
}
