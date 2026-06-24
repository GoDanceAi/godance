package ai.godance.routing;

import java.util.Set;

import ai.godance.domain.Provider;

/**
 * Hard ceilings applied to every routing decision, even user-authored
 * ones (design §3.3). {@code allowedProviders}/{@code excludedProviders}
 * also serve as the Phase 3 compliance opt-out ("exclude these
 * providers"). The region allowlist is NOT one of these — that stays
 * solely in {@code ProviderGateway.dispatch()} (design §11).
 */
public record RoutingLimits(
        int maxCandidates,                 // truncate the ordered candidate list to this
        Long maxEstimateCentsCeiling,      // refuse a candidate whose estimate exceeds this; null = no ceiling
        Set<Provider> allowedProviders,    // if non-empty, a candidate's provider must be in here
        Set<Provider> excludedProviders    // candidate dropped if its provider is in here
) {
    public static final RoutingLimits DEFAULTS =
            new RoutingLimits(4, null, Set.of(), Set.of());

    public RoutingLimits {
        if (maxCandidates < 1) maxCandidates = 1;
        allowedProviders  = allowedProviders  == null ? Set.of() : Set.copyOf(allowedProviders);
        excludedProviders = excludedProviders == null ? Set.of() : Set.copyOf(excludedProviders);
    }
}
