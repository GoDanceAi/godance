package ai.godance.web.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import ai.godance.gateway.ChatDtos.ChatChoice;
import ai.godance.gateway.ChatDtos.ChatMessage;
import ai.godance.gateway.ChatDtos.ChatResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/**
 * OpenAI-compatible wire shapes — clients should be able to swap
 * {@code base_url} from {@code api.openai.com} to Godance and have
 * existing SDK code keep working.
 */
public final class ChatCompletionDtos {

    private ChatCompletionDtos() { }

    public record ChatCompletionRequest(
            @NotBlank String model,
            @NotEmpty List<@Valid Message> messages,
            @JsonProperty("max_tokens") Integer maxTokens,
            Double temperature,
            Boolean stream
    ) { }

    public record Message(@NotBlank String role, @NotBlank String content) { }

    public record ChatCompletionResponse(
            String id,
            String object,
            long created,
            String model,
            List<Choice> choices,
            UsageBlock usage
    ) {
        public static ChatCompletionResponse from(ChatResponse r) {
            return new ChatCompletionResponse(
                    r.id(),
                    "chat.completion",
                    r.createdAt(),
                    r.model(),
                    r.choices().stream().map(ChatCompletionResponse::toChoice).toList(),
                    new UsageBlock(
                            r.usage().promptTokens(),
                            r.usage().completionTokens(),
                            r.usage().totalTokens()));
        }

        private static Choice toChoice(ChatChoice c) {
            ChatMessage m = c.message();
            return new Choice(c.index(),
                    new Message(m.role(), m.content()),
                    c.finishReason());
        }
    }

    public record Choice(
            int index,
            Message message,
            @JsonProperty("finish_reason") String finishReason
    ) { }

    public record UsageBlock(
            @JsonProperty("prompt_tokens") int promptTokens,
            @JsonProperty("completion_tokens") int completionTokens,
            @JsonProperty("total_tokens") int totalTokens
    ) { }

    // ===== Streaming wire shape (OpenAI-compatible chunk) =============

    public record ChatCompletionChunk(
            String id,
            String object,
            long created,
            String model,
            List<ChunkChoice> choices,
            UsageBlock usage              // null on intermediate chunks
    ) {
        public static ChatCompletionChunk from(ai.godance.gateway.ChatDtos.ChatChunk c) {
            List<ChunkChoice> choices = c.choices().stream()
                    .map(ChatCompletionChunk::toChoice)
                    .toList();
            UsageBlock u = c.usage() == null ? null
                    : new UsageBlock(c.usage().promptTokens(),
                                     c.usage().completionTokens(),
                                     c.usage().totalTokens());
            return new ChatCompletionChunk(c.id(), "chat.completion.chunk",
                    c.createdAt(), c.model(), choices, u);
        }

        private static ChunkChoice toChoice(ai.godance.gateway.ChatDtos.ChunkChoice c) {
            ai.godance.gateway.ChatDtos.ChunkDelta d = c.delta();
            return new ChunkChoice(c.index(),
                    new ChunkDeltaBlock(d == null ? null : d.role(),
                                        d == null ? null : d.content()),
                    c.finishReason());
        }
    }

    public record ChunkChoice(
            int index,
            ChunkDeltaBlock delta,
            @JsonProperty("finish_reason") String finishReason
    ) { }

    public record ChunkDeltaBlock(String role, String content) { }
}
