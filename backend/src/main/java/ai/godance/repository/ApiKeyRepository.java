package ai.godance.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import ai.godance.domain.ApiKey;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {
    List<ApiKey> findByUserId(UUID userId);
    Optional<ApiKey> findByKeyHash(String keyHash);

    long countByUserId(UUID userId);

    /** Revoke every key a user holds (password reset). Returns how many were deleted. */
    @Modifying
    @Query("DELETE FROM ApiKey k WHERE k.userId = :userId")
    int deleteByUserId(@Param("userId") UUID userId);
}
