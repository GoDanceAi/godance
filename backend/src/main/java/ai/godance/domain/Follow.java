package ai.godance.domain;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * One account following another (V39). Who follows whom is never shown to
 * anyone but the follower: a profile shows only how many follow it.
 */
@Entity
@Table(name = "follows")
@IdClass(Follow.Key.class)
public class Follow {

    /** The primary key: the follower and the followed account. */
    public record Key(UUID followerId, UUID followeeId) implements Serializable {
        public Key() {
            this(null, null);
        }
    }

    @Id
    @Column(name = "follower_id", nullable = false, updatable = false)
    private UUID followerId;

    @Id
    @Column(name = "followee_id", nullable = false, updatable = false)
    private UUID followeeId;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    public UUID getFollowerId() { return followerId; }

    public UUID getFolloweeId() { return followeeId; }

    public Instant getCreatedAt() { return createdAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Follow other)) return false;
        return Objects.equals(followerId, other.followerId) && Objects.equals(followeeId, other.followeeId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(followerId, followeeId);
    }
}
