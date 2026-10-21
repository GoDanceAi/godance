package ai.godance.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import ai.godance.gateway.ChatDtos.ChatMessage;
import ai.godance.gateway.ChatDtos.ChatRequest;

/**
 * The heuristic classifier is intentionally dumb (design §4.3); these
 * tests pin the keyword/length signals the presets branch on.
 */
class HeuristicTaskClassifierTest {

    private final TaskClassifier classifier = new HeuristicTaskClassifier();

    private static ChatRequest req(String content) {
        return new ChatRequest("godance-auto",
                List.of(new ChatMessage("user", content)), null, null, null);
    }

    @Test
    void classify_givenCodeFence_returnsCode() {
        assertThat(classifier.classify(req("```python\nprint(1)\n```"))).isEqualTo(TaskType.CODE);
    }

    @Test
    void classify_givenDefKeyword_returnsCode() {
        assertThat(classifier.classify(req("please finish def add(a, b): ..."))).isEqualTo(TaskType.CODE);
    }

    @Test
    void classify_givenFunctionKeyword_returnsCode() {
        assertThat(classifier.classify(req("write a function add(a, b)"))).isEqualTo(TaskType.CODE);
    }

    @Test
    void classify_givenLongInput_returnsLongContext() {
        assertThat(classifier.classify(req("word ".repeat(6_000)))).isEqualTo(TaskType.LONG_CONTEXT);
    }

    @Test
    void classify_givenReasoningCue_returnsReasoning() {
        assertThat(classifier.classify(req("prove the result, step by step")))
                .isEqualTo(TaskType.REASONING);
    }

    @Test
    void classify_givenPlainChat_returnsChat() {
        assertThat(classifier.classify(req("Hi there, how are you today?"))).isEqualTo(TaskType.CHAT);
    }
}
