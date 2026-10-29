package ai.godance.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.domain.Conversation;
import ai.godance.domain.ConversationMessage;
import ai.godance.repository.ConversationMessageRepository;
import ai.godance.repository.ConversationRepository;

@Service
public class ConversationService {

    private final ConversationRepository conversations;
    private final ConversationMessageRepository messages;

    public ConversationService(ConversationRepository conversations,
                               ConversationMessageRepository messages) {
        this.conversations = conversations;
        this.messages = messages;
    }

    @Transactional
    public Conversation create(UUID userId, String title) {
        Conversation c = new Conversation();
        c.setUserId(userId);
        c.setTitle(title == null || title.isBlank() ? "New conversation" : title.trim());
        return conversations.save(c);
    }

    @Transactional(readOnly = true)
    public List<Conversation> listActive(UUID userId) {
        return conversations.findByUserIdAndArchivedAtIsNullOrderByUpdatedAtDesc(userId);
    }

    /** Returns the conversation if the caller owns it; otherwise 404 (no leak). */
    @Transactional(readOnly = true)
    public Conversation requireOwned(UUID userId, UUID conversationId) {
        Conversation c = conversations.findById(conversationId)
                .filter(x -> x.getUserId().equals(userId))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "conversation not found"));
        return c;
    }

    @Transactional(readOnly = true)
    public List<ConversationMessage> listMessages(UUID userId, UUID conversationId) {
        requireOwned(userId, conversationId);
        return messages.findByConversationIdOrderByCreatedAt(conversationId);
    }

    @Transactional
    public ConversationMessage appendMessage(UUID userId, UUID conversationId, AppendInput input) {
        Conversation c = requireOwned(userId, conversationId);
        if (input.role() == null || input.content() == null || input.content().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "role + content required");
        }
        String role = input.role().toLowerCase();
        if (!role.equals("system") && !role.equals("user") && !role.equals("assistant")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "role must be system | user | assistant");
        }

        ConversationMessage m = new ConversationMessage();
        m.setConversationId(conversationId);
        m.setRole(role);
        m.setContent(input.content());
        m.setModel(input.model());
        m.setInputTokens(input.inputTokens());
        m.setOutputTokens(input.outputTokens());
        m.setCostCents(input.costCents());
        ConversationMessage saved = messages.save(m);

        // Bumping updated_at on the parent keeps listActive() ordering honest.
        c.setUpdatedAt(Instant.now());
        conversations.save(c);

        return saved;
    }

    /**
     * Archives the conversation. {@link DataRetentionJob} deletes it, with
     * its messages, 30 days after archiving.
     */
    @Transactional
    public void archive(UUID userId, UUID conversationId) {
        Conversation c = requireOwned(userId, conversationId);
        if (c.getArchivedAt() == null) {
            c.setArchivedAt(Instant.now());
            conversations.save(c);
        }
    }

    /** Fields a caller may set when appending. Assistant-only fields are nullable for user messages. */
    public record AppendInput(
            String role,
            String content,
            String model,
            Integer inputTokens,
            Integer outputTokens,
            Long costCents
    ) { }
}
