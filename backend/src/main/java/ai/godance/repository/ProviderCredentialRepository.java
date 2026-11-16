package ai.godance.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import ai.godance.domain.Provider;
import ai.godance.domain.ProviderCredential;

public interface ProviderCredentialRepository extends JpaRepository<ProviderCredential, UUID> {

    Optional<ProviderCredential> findByUserIdAndProvider(UUID userId, Provider provider);

    List<ProviderCredential> findByUserIdOrderByProviderAsc(UUID userId);

    /** Whether the user holds a key for this provider that was valid when last checked: no decryption. */
    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM provider_credentials
                            WHERE user_id = :userId AND provider = :provider AND status = 'valid')
            """, nativeQuery = true)
    boolean hasValid(@Param("userId") UUID userId, @Param("provider") String provider);

    /** Marks the key invalid: its provider refused it. Returns the rows changed. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE provider_credentials SET status = 'invalid', checked_at = :now, updated_at = :now
             WHERE id = :id AND status <> 'invalid'
            """, nativeQuery = true)
    int markInvalid(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM provider_credentials WHERE user_id = :userId AND provider = :provider", nativeQuery = true)
    int deleteByUserIdAndProvider(@Param("userId") UUID userId, @Param("provider") String provider);

    /** Every key the account holds: closing it deletes them. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM provider_credentials WHERE user_id = :userId", nativeQuery = true)
    int deleteByUserId(@Param("userId") UUID userId);
}
