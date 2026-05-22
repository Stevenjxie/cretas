package com.cretas.aims.repository.indicator;

import com.cretas.aims.entity.indicator.IndicatorComputation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 指标计算策略 Repository — 合并自 Phase 1 D3 + Canvas Phase A (2026-05-22).
 *
 * <p>把指标连接到实际计算源 (SpEL 公式 / Python 端点 / JPA query)。
 * 同一指标允许多条策略 (按 priority 排序, 主备 fallback)。
 */
@Repository
public interface IndicatorComputationRepository extends JpaRepository<IndicatorComputation, String> {

    // Phase 1 D3 — Service layer 入口 (@Query-based, soft-delete-aware)

    @Query("SELECT c FROM IndicatorComputation c " +
           "WHERE c.indicatorId = :indicatorId " +
           "AND c.isActive = true " +
           "ORDER BY c.priority ASC")
    List<IndicatorComputation> findByIndicatorIdAndIsActiveTrueAndDeletedAtIsNull(
            @Param("indicatorId") String indicatorId);

    @Query("SELECT c FROM IndicatorComputation c " +
           "WHERE c.computeType = :computeType " +
           "AND c.isActive = true")
    List<IndicatorComputation> findActiveByComputeType(@Param("computeType") String computeType);

    @Query("SELECT c FROM IndicatorComputation c " +
           "WHERE c.indicatorId = :indicatorId " +
           "AND c.isActive = true " +
           "ORDER BY c.priority ASC")
    List<IndicatorComputation> findPrimaryByIndicatorId(@Param("indicatorId") String indicatorId);

    Optional<IndicatorComputation> findByIdAndDeletedAtIsNull(String id);

    // Canvas Phase A — Spring Data method-name 入口

    /** 按优先级排序 (主备 fallback) — Canvas-side simpler signature. */
    List<IndicatorComputation> findByIndicatorIdAndIsActiveTrueOrderByPriorityAsc(String indicatorId);

    /** 主策略 (priority=1) — Canvas-side direct primary lookup. */
    Optional<IndicatorComputation> findFirstByIndicatorIdAndIsActiveTrueOrderByPriorityAsc(String indicatorId);
}
