package ai.godance.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import ai.godance.domain.CatalogStatus;
import ai.godance.domain.ModelCatalogEntry;

public interface ModelCatalogEntryRepository extends JpaRepository<ModelCatalogEntry, Long> {

    Optional<ModelCatalogEntry> findByModelName(String modelName);

    List<ModelCatalogEntry> findByStatus(CatalogStatus status);
}
