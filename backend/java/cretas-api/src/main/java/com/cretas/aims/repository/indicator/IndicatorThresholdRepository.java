package com.cretas.aims.repository.indicator;

import com.cretas.aims.entity.indicator.IndicatorThreshold;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 指标阈值 Repository — GREEN / YELLOW / RED 三色预警配置。
 */
@Repository
public interface IndicatorThresholdRepository extends JpaRepository<IndicatorThreshold, String> {

    /** 指标的全部活跃阈值。 */
    List<IndicatorThreshold> findByIndicatorIdAndIsActiveTrue(String indicatorId);

    /** 工厂内全部阈值 (admin 视角列表)。 */
    List<IndicatorThreshold> findByFactoryIdAndIsActiveTrue(String factoryId);

    /**
     * Sprint 11 hotfix — RLS-safe: indicator's active thresholds within a specific factory.
     * Used by IndicatorController.getValue / getThresholds (PR #155 cherry-picked but method
     * was missing from PR #153 repo cherry-pick).
     * Uses @Query (not derivation) because "findActiveBy" is not a JPA derivation keyword.
     */
    @Query("SELECT t FROM IndicatorThreshold t " +
           "WHERE t.indicatorId = :indicatorId " +
           "  AND t.factoryId = :factoryId " +
           "  AND t.isActive = true")
    List<IndicatorThreshold> findActiveByIndicatorIdAndFactoryId(
            @Param("indicatorId") String indicatorId,
            @Param("factoryId") String factoryId);
}
