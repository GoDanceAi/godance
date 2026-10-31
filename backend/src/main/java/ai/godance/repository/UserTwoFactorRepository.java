package ai.godance.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import ai.godance.domain.UserTwoFactor;
import jakarta.persistence.LockModeType;

public interface UserTwoFactorRepository extends JpaRepository<UserTwoFactor, UUID> {

    /** Whether the account has 2FA on: a row whose enrolment was confirmed. */
    boolean existsByUserIdAndEnabledAtIsNotNull(UUID userId);

    /**
     * The row, locked until the transaction ends. Every check of a code
     * reads through this, so two attempts on one account can't both spend
     * one code, or both read the same failed-attempt count.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM UserTwoFactor t WHERE t.userId = :userId")
    Optional<UserTwoFactor> findForUpdate(@Param("userId") UUID userId);
}
