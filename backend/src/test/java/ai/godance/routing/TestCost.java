package ai.godance.routing;

import ai.godance.config.BillingProperties;
import ai.godance.gateway.CostCalculator;

/** The cost calculator the routing unit tests give the engine: 5% on text, as in CostCalculatorTest. */
final class TestCost {

    static final CostCalculator CALCULATOR = new CostCalculator(
            new BillingProperties(100L, 100_000L, "usd", 5.0, 10.0, 12.0));

    private TestCost() { }
}
