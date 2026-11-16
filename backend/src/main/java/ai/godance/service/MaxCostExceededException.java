package ai.godance.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * A request that would cost more than the most its caller said it may
 * ({@code max_cost_cents}, round 13): a 402 problem with {@code code}
 * {@code max_cost_exceeded}, the request's price in
 * {@code estimated_cost_cents} and the ceiling in {@code max_cost_cents}.
 *
 * <p>It is thrown where the price is taken, before the pre-debit, so nothing
 * is charged and no job is written. A request without a ceiling is never
 * refused by it.
 */
public final class MaxCostExceededException extends ResponseStatusException {

    public static final String CODE = "max_cost_exceeded";

    private final long estimatedCostCents;
    private final long maxCostCents;

    public MaxCostExceededException(long estimatedCostCents, long maxCostCents) {
        super(HttpStatus.PAYMENT_REQUIRED, "This costs " + estimatedCostCents
                + " cents, more than max_cost_cents (" + maxCostCents + "). Nothing was charged.");
        this.estimatedCostCents = estimatedCostCents;
        this.maxCostCents = maxCostCents;
        getBody().setProperty("code", CODE);
        getBody().setProperty("estimated_cost_cents", estimatedCostCents);
        getBody().setProperty("max_cost_cents", maxCostCents);
    }

    /**
     * Refuses a price above the ceiling. A null ceiling allows any price.
     *
     * @throws MaxCostExceededException when {@code costCents} is above {@code maxCostCents}
     */
    public static void requireWithin(long costCents, Long maxCostCents) {
        if (maxCostCents != null && costCents > maxCostCents) {
            throw new MaxCostExceededException(costCents, maxCostCents);
        }
    }

    public long estimatedCostCents() {
        return estimatedCostCents;
    }

    public long maxCostCents() {
        return maxCostCents;
    }
}
