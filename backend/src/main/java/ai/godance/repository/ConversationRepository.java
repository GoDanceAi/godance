package ai.godance.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import ai.godance.domain.Conversation;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {
    /** Active (non-archived) conversations, newest first. */
    List<Conversation> findByUserIdAndArchivedAtIsNullOrderByUpdatedAtDesc(UUID userId);

    // Deleting a conversation deletes its messages too (V7: ON DELETE CASCADE).

    /** Data retention: deletes up to {@code limit} conversations archived before {@code cutoff}. */
    @Transactional
    @Modifying
    @Query(value = """
            DELETE FROM conversations
             WHERE id IN (SELECT id FROM conversations
                           WHERE archived_at < :cutoff
                           ORDER BY archived_at
                           LIMIT :limit)
            """, nativeQuery = true)
    int deleteArchivedBefore(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

    /** Account closure: deletes every conversation the user has, active or archived. */
    @Modifying
    @Query(value = "DELETE FROM conversations WHERE user_id = :userId", nativeQuery = true)
    int deleteByUserId(@Param("userId") UUID userId);
}
