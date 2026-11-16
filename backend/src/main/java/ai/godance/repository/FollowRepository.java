package ai.godance.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import ai.godance.domain.Follow;

public interface FollowRepository extends JpaRepository<Follow, Follow.Key> {

    /** Follows; following an account already followed changes nothing. Returns the rows added. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO follows (follower_id, followee_id) VALUES (:followerId, :followeeId)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int follow(@Param("followerId") UUID followerId, @Param("followeeId") UUID followeeId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM follows WHERE follower_id = :followerId AND followee_id = :followeeId",
            nativeQuery = true)
    int unfollow(@Param("followerId") UUID followerId, @Param("followeeId") UUID followeeId);

    long countByFolloweeId(UUID followeeId);

    /** How many follow each of these accounts. */
    interface FollowerCount {
        UUID getFolloweeId();
        long getFollowers();
    }

    @Query(value = """
            SELECT f.followee_id AS followeeId, count(*) AS followers
              FROM follows f
             WHERE f.followee_id IN (:ids)
             GROUP BY f.followee_id
            """, nativeQuery = true)
    List<FollowerCount> followerCounts(@Param("ids") Collection<UUID> ids);

    /** The handles of the accounts {@code followerId} follows, in the order followed, newest first. */
    @Query(value = """
            SELECT p.handle FROM follows f
              JOIN profiles p ON p.user_id = f.followee_id
             WHERE f.follower_id = :followerId
             ORDER BY f.created_at DESC, p.handle
            """, nativeQuery = true)
    List<String> followedHandles(@Param("followerId") UUID followerId);

    /** Every follow by or of the account: what closing it removes. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM follows WHERE follower_id = :userId OR followee_id = :userId", nativeQuery = true)
    int deleteByAccount(@Param("userId") UUID userId);
}
