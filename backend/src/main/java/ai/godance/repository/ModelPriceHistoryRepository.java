package ai.godance.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import ai.godance.domain.ModelPriceHistory;

public interface ModelPriceHistoryRepository extends JpaRepository<ModelPriceHistory, Long> {

    List<ModelPriceHistory> findByModelNameOrderByObservedAtDesc(String modelName);
}
