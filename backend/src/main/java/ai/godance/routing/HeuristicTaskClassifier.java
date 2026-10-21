package ai.godance.routing;

import org.springframework.stereotype.Component;

import ai.godance.gateway.ChatDtos.ChatMessage;
import ai.godance.gateway.ChatDtos.ChatRequest;

/**
 * Cheap, deterministic keyword/length heuristic (design §4.3).
 * Intentionally dumb and fast: a long concatenated prompt is
 * {@code LONG_CONTEXT}; obvious code markers make it {@code CODE};
 * reasoning cue words make it {@code REASONING}; everything else is
 * {@code CHAT}. Accuracy is the thing to measure later (design §8) —
 * this is the swappable seam, not the final word.
 */
@Component
class HeuristicTaskClassifier implements TaskClassifier {

    private static final int LONG_CONTEXT_CHARS = 24_000;

    @Override
    public TaskType classify(ChatRequest request) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : request.messages()) {
            if (m.content() != null) sb.append(m.content()).append('\n');
        }
        String text = sb.toString();
        if (text.length() >= LONG_CONTEXT_CHARS) return TaskType.LONG_CONTEXT;

        String lower = text.toLowerCase();
        if (lower.contains("```") || lower.contains("def ") || lower.contains("function ")
                || lower.contains("import ") || lower.contains("public class")) return TaskType.CODE;
        if (lower.contains("prove") || lower.contains("step by step")
                || lower.contains("reason") || lower.contains("calculate")) return TaskType.REASONING;
        return TaskType.CHAT;
    }
}
