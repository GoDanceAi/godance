package ai.godance.routing;

import ai.godance.gateway.ChatDtos.ChatRequest;

/**
 * Infers a coarse {@link TaskType} from a chat request so the engine can
 * pick a branch (design §4.3). An interface so a learned classifier can
 * replace the heuristic without touching the engine or the service.
 */
public interface TaskClassifier {
    TaskType classify(ChatRequest request);
}
