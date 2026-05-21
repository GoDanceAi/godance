package ai.godance.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import ai.godance.domain.OAuthIdentity;
import ai.godance.domain.OAuthProvider;

public interface OAuthIdentityRepository extends JpaRepository<OAuthIdentity, UUID> {
    Optional<OAuthIdentity> findByProviderAndSubject(OAuthProvider provider, String subject);
}
