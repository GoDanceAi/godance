package ai.godance.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * A billable request refused because the balance can't cover its estimate:
 * a 402 whose problem carries {@code "code":"insufficient_credit"}.
 *
 * <p>A 402 can also be a spending cap ({@link SpendingLimitService}), where
 * adding credit doesn't help. The code lets a client tell the two apart
 * without reading {@code detail}: the SPA sends this one to the Billing page.
 */
public final class InsufficientCreditException extends ResponseStatusException {

    /** The problem's {@code code} extension member. */
    public static final String CODE = "insufficient_credit";

    public InsufficientCreditException() {
        super(HttpStatus.PAYMENT_REQUIRED, "insufficient credit; top up via /v1/billing/topup");
        getBody().setProperty("code", CODE);
    }
}
