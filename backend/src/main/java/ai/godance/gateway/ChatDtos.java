package ai.godance.gateway;

import java.util.List;

/**
 * Provider-agnostic chat completion shapes. Modeled after OpenAI's
 * v1 chat completions so future adapters (Anthropic, Vertex, Volcengine
 * Ark) translate in/out at the edge rather than the controller layer.
 */
public final class ChatDtos {

    private ChatDtos() { }

    public record ChatRequest(
            String model,
            List<ChatMessage> messages,
            Integer maxTokens,
            Double temperature,
            Boolean stream            // null/false in Session 6; SSE arrives Session 7
    ) { }

    public record ChatMessage(String role, String content) { }   // role: system | user | assistant

    public record ChatResponse(
            String id,
            String model,
            List<ChatChoice> choices,
            Usage usage,
            long createdAt            // unix seconds
    ) { }

    public record ChatChoice(int index, ChatMessage message, String finishReason) { }

    public record Usage(int promptTokens, int completionTokens, int totalTokens) { }

    // ===== Streaming =================================================

    /**
     * Incremental chunk emitted while a streamed completion is in
     * flight. {@code usage} is null on every chunk except the terminal
     * one — providers either inline it on the final chunk or emit a
     * trailing usage-only chunk (OpenAI does the latter when
     * {@code stream_options.include_usage = true}).
     */
    public record ChatChunk(
            String id,
            String model,
            long createdAt,
            java.util.List<ChunkChoice> choices,
            Usage usage
    ) { }

    public record ChunkChoice(int index, ChunkDelta delta, String finishReason) { }

    /** {@code role} appears once on the first chunk; subsequent chunks carry only {@code content}. */
    public record ChunkDelta(String role, String content) { }
}
