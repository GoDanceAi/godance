package ai.godance.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import ai.godance.domain.OAuthIdentity;
import ai.godance.domain.OAuthProvider;

public interface OAuthIdentityRepository extends JpaRepository<OAuthIdentity, UUID> {
    Optional<OAuthIdentity> findByProviderAndSubject(OAuthProvider provider, String subject);

    /** Unlink every social login a user has (account closure). Returns how many were deleted. */
    @Modifying
    @Query("DELETE FROM OAuthIdentity i WHERE i.userId = :userId")
    int deleteByUserId(@Param("userId") UUID userId);
}
