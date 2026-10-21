package ai.godance.routing;

import ai.godance.domain.Modality;
import ai.godance.domain.Region;

/**
 * Signals available to the engine at decision time (design §3.4). Built
 * entirely from data the service already has — no new I/O.
 */
public record RoutingContext(
        String requestedModel,     // the virtual id the caller asked for
        Modality modality,
        int estimatedInputTokens,  // ChatCompletionService.estimateInputTokens(messages)
        int messageCount,
        TaskType task,             // from TaskClassifier (or a hint override)
        String routeHint,          // optional X-Godance-Route hint; null until Phase 3 wires it
        Region region              // gateway.region(); for limit + audit context
) { }
