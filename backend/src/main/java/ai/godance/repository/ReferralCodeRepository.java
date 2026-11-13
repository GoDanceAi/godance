package ai.godance.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import ai.godance.domain.ReferralCode;

public interface ReferralCodeRepository extends JpaRepository<ReferralCode, UUID> {

    /** Whose code it is. */
    @Query("SELECT c.userId FROM ReferralCode c WHERE c.code = :code")
    Optional<UUID> findOwnerByCode(@Param("code") String code);

    /** The user's code, if it has one. A query, so it always reads the database. */
    @Query("SELECT c.code FROM ReferralCode c WHERE c.userId = :userId")
    Optional<String> findCodeByUserId(@Param("userId") UUID userId);

    /**
     * Gives {@code userId} the code {@code code}, unless the user already
     * has one or another user holds that code: 1 if written, else 0.
     * Never fails on either conflict, so the caller's transaction stays
     * usable and can read back which one it was. Must run in a transaction.
     */
    @Modifying
    @Query(value = "INSERT INTO referral_codes (user_id, code) VALUES (:userId, :code) ON CONFLICT DO NOTHING",
           nativeQuery = true)
    int insertIfAbsent(@Param("userId") UUID userId, @Param("code") String code);
}
