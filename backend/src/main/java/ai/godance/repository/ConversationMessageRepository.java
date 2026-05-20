package ai.godance.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import ai.godance.domain.ConversationMessage;

public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, UUID> {
    List<ConversationMessage> findByConversationIdOrderByCreatedAt(UUID conversationId);
}
