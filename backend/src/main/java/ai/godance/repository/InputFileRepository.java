package ai.godance.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import ai.godance.domain.InputFile;

/**
 * Input files (V31). "In use" is read off {@code generation_job_inputs}
 * alone: a file is in use exactly while a row there names it, and those
 * rows are deleted when their job is purged.
 *
 * <p>Round 11 brief 05 gives recipes input slots that hold no file. If a
 * recipe ever keeps one, its table joins the three {@code NOT EXISTS}
 * checks below, and nothing else changes.
 */
public interface InputFileRepository extends JpaRepository<InputFile, UUID> {

    /** One of the user's files that still has its object. Another user's is not found. */
    @Query(value = "SELECT * FROM input_files WHERE id = :id AND user_id = :userId AND deleted_at IS NULL",
           nativeQuery = true)
    Optional<InputFile> findLive(@Param("id") UUID id, @Param("userId") UUID userId);

    /** {@link #findLive}, with the row locked until the caller's transaction ends. */
    @Query(value = """
            SELECT * FROM input_files
             WHERE id = :id AND user_id = :userId AND deleted_at IS NULL
               FOR UPDATE
            """, nativeQuery = true)
    Optional<InputFile> lockLive(@Param("id") UUID id, @Param("userId") UUID userId);

    /** One page of a user's files, newest first, strictly older than the cursor. */
    @Query(value = """
            SELECT * FROM input_files f
             WHERE f.user_id = :userId
               AND f.deleted_at IS NULL
               AND (f.created_at, f.id) < (:beforeCreatedAt, :beforeId)
             ORDER BY f.created_at DESC, f.id DESC
             LIMIT :limit
            """, nativeQuery = true)
    List<InputFile> findPage(@Param("userId") UUID userId,
                             @Param("beforeCreatedAt") Instant beforeCreatedAt,
                             @Param("beforeId") UUID beforeId,
                             @Param("limit") int limit);

    /** How many files the user has stored: what the per-account limit counts. */
    @Query(value = "SELECT COUNT(*) FROM input_files WHERE user_id = :userId AND deleted_at IS NULL",
           nativeQuery = true)
    long countLive(@Param("userId") UUID userId);

    /** Every file row of a user, deleted or not (account closure removes them all). */
    List<InputFile> findByUserId(UUID userId);

    /**
     * Marks the user's files as in use: no expiry. Returns how many of
     * {@code ids} were the user's and still had their object; a caller that
     * gets fewer than it asked for must not go on. The rows stay locked
     * until the caller's transaction ends, so the nightly run cannot delete
     * one between this and the rows that record the use.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE input_files
               SET expires_at = NULL
             WHERE id IN (:ids) AND user_id = :userId AND deleted_at IS NULL
            """, nativeQuery = true)
    int markInUse(@Param("ids") Collection<UUID> ids, @Param("userId") UUID userId);

    /** Unused files past their time, oldest first: what the nightly run deletes. */
    @Query(value = """
            SELECT * FROM input_files f
             WHERE f.deleted_at IS NULL
               AND f.expires_at IS NOT NULL
               AND f.expires_at < :now
               AND (f.expires_at, f.id) > (:afterExpiresAt, :afterId)
             ORDER BY f.expires_at, f.id
             LIMIT :limit
            """, nativeQuery = true)
    List<InputFile> findExpired(@Param("now") Instant now,
                                @Param("afterExpiresAt") Instant afterExpiresAt,
                                @Param("afterId") UUID afterId,
                                @Param("limit") int limit);

    /**
     * Locks the file's row and returns its id only while the nightly run may
     * still delete it: its object is there, its time has passed and nothing
     * uses it. A use recorded while this waited makes it come back empty.
     */
    @Query(value = """
            SELECT f.id FROM input_files f
             WHERE f.id = :id
               AND f.deleted_at IS NULL
               AND f.expires_at IS NOT NULL
               AND f.expires_at < :now
               AND NOT EXISTS (SELECT 1 FROM generation_job_inputs i WHERE i.file_id = f.id)
               FOR UPDATE
            """, nativeQuery = true)
    Optional<UUID> lockIfExpiredAndUnused(@Param("id") UUID id, @Param("now") Instant now);

    /** Whether any job still records the file. */
    @Query(value = "SELECT EXISTS (SELECT 1 FROM generation_job_inputs WHERE file_id = :id)", nativeQuery = true)
    boolean isInUse(@Param("id") UUID id);

    /** The object is gone: no link can be signed for the file any more. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE input_files
               SET deleted_at = :now, expires_at = NULL
             WHERE id = :id AND deleted_at IS NULL
            """, nativeQuery = true)
    int markDeleted(@Param("id") UUID id, @Param("now") Instant now);

    /** Deletes the row, and with it (ON DELETE CASCADE) any record of its use. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM input_files WHERE id = :id", nativeQuery = true)
    int deleteRow(@Param("id") UUID id);

    /**
     * Of {@code ids}, deletes the rows whose object is already gone and that
     * no job records any more: nothing is left to say about them.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            DELETE FROM input_files f
             WHERE f.id IN (:ids)
               AND f.deleted_at IS NOT NULL
               AND NOT EXISTS (SELECT 1 FROM generation_job_inputs i WHERE i.file_id = f.id)
            """, nativeQuery = true)
    int deleteGoneAndUnused(@Param("ids") Collection<UUID> ids);

    /**
     * Of {@code ids}, gives the files that were in use and that no job
     * records any more a fresh expiry: they are unused again.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE input_files f
               SET expires_at = :expiresAt
             WHERE f.id IN (:ids)
               AND f.deleted_at IS NULL
               AND f.expires_at IS NULL
               AND NOT EXISTS (SELECT 1 FROM generation_job_inputs i WHERE i.file_id = f.id)
            """, nativeQuery = true)
    int expireUnused(@Param("ids") Collection<UUID> ids, @Param("expiresAt") Instant expiresAt);
}
