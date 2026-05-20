package ai.godance.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import ai.godance.domain.ApiKey;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {
    List<ApiKey> findByUserId(UUID userId);
    Optional<ApiKey> findByKeyHash(String keyHash);
}
