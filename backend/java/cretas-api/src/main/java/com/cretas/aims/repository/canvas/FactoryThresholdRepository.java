package com.cretas.aims.repository.canvas;

import com.cretas.aims.entity.canvas.FactoryThreshold;
import com.cretas.aims.entity.canvas.ThresholdCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link FactoryThreshold}.
 *
 * @since Canvas Phase A (2026-05-21)
 */
@Repository
public interface FactoryThresholdRepository extends JpaRepository<FactoryThreshold, UUID> {

    /**
     * Per-factory + threshold-key lookup (resolver hot path).
     * Backed by partial unique index {@code idx_factory_thresholds_unique}.
     */
    Optional<FactoryThreshold> findByFactoryIdAndThresholdKey(String factoryId, String thresholdKey);

    /**
     * Per-factory list filtered by category (UI sub-tab loading).
     */
    List<FactoryThreshold> findByFactoryIdAndCategoryOrderByThresholdKey(
            String factoryId, ThresholdCategory category);

    /**
     * Per-factory list (all categories, used for full export / batch reset).
     */
    List<FactoryThreshold> findByFactoryIdOrderByCategoryAscThresholdKeyAsc(String factoryId);
}
