package ai.godance.routing;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Users' own routing policies (V41). */
public interface RoutingPolicyRepository extends JpaRepository<RoutingPolicyEntity, String> {

    List<RoutingPolicyEntity> findByUserIdOrderByNameAsc(UUID userId);

    long countByUserId(UUID userId);

    /** Account closure: every policy of the account. */
    @Modifying
    @Query("DELETE FROM RoutingPolicyEntity p WHERE p.userId = :userId")
    int deleteByUserId(@Param("userId") UUID userId);
}
