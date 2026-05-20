package ai.godance.web.dto;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import ai.godance.domain.Conversation;
import ai.godance.domain.ConversationMessage;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class ConversationDtos {

    private ConversationDtos() { }

    public record CreateConversationRequest(@Size(max = 255) String title) { }

    public record ConversationView(
            UUID id,
            String title,
            Instant createdAt,
            Instant updatedAt,
            Instant archivedAt
    ) {
        public static ConversationView from(Conversation c) {
            return new ConversationView(c.getId(), c.getTitle(),
                    c.getCreatedAt(), c.getUpdatedAt(), c.getArchivedAt());
        }
    }

    public record AppendMessageRequest(
            @NotBlank String role,
            @NotBlank String content,
            String model,
            @JsonProperty("input_tokens")  Integer inputTokens,
            @JsonProperty("output_tokens") Integer outputTokens,
            @JsonProperty("cost_cents")    Long costCents
    ) { }

    public record MessageView(
            UUID id,
            String role,
            String content,
            String model,
            @JsonProperty("input_tokens")  Integer inputTokens,
            @JsonProperty("output_tokens") Integer outputTokens,
            @JsonProperty("cost_cents")    Long costCents,
            Instant createdAt
    ) {
        public static MessageView from(ConversationMessage m) {
            return new MessageView(m.getId(), m.getRole(), m.getContent(), m.getModel(),
                    m.getInputTokens(), m.getOutputTokens(), m.getCostCents(),
                    m.getCreatedAt());
        }
    }
}
