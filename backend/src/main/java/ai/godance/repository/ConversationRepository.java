package ai.godance.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import ai.godance.domain.Conversation;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {
    /** Active (non-archived) conversations, newest first. */
    List<Conversation> findByUserIdAndArchivedAtIsNullOrderByUpdatedAtDesc(UUID userId);
}
