package com.cretas.aims.repository.indicator;

import com.cretas.aims.entity.indicator.IndicatorVersion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 指标历史快照 Repository — append-only audit log.
 *
 * <p>合并自 Phase 1 D4 (PR #154) + Canvas Phase A (PR #181):
 * <ul>
 *   <li>D4 Service 层: findByIndicatorIdAndFactoryIdAndPeriodOverlap / findRecentByFactoryId</li>
 *   <li>Canvas Phase A: findByIndicatorIdOrderByComputedAtDesc (paged) / findInWindow / findFirstByIndicatorIdOrderByComputedAtDesc</li>
 * </ul>
 *
 * <p>每次重算落库一行. 用于趋势图 + 月报 + 合规审计 + version rollback.
 * Entity 层 @PreRemove 阻止物理删除.
 */
@Repository
public interface IndicatorVersionRepository extends JpaRepository<IndicatorVersion, String> {

    // Phase 1 D4 — Service 层入口 (windowed by business period)

    @Query("SELECT v FROM IndicatorVersion v " +
           "WHERE v.indicatorId = :indicatorId " +
           "AND v.factoryId = :factoryId " +
           "AND v.periodEnd >= :windowStart " +
           "AND v.periodStart <= :windowEnd " +
           "ORDER BY v.computedAt DESC")
    List<IndicatorVersion> findByIndicatorIdAndFactoryIdAndPeriodOverlap(
            @Param("indicatorId") String indicatorId,
            @Param("factoryId") String factoryId,
            @Param("windowStart") LocalDate windowStart,
            @Param("windowEnd") LocalDate windowEnd);

    @Query("SELECT v FROM IndicatorVersion v " +
           "WHERE v.factoryId = :factoryId " +
           "ORDER BY v.computedAt DESC")
    List<IndicatorVersion> findRecentByFactoryId(@Param("factoryId") String factoryId);

    // Canvas Phase A — paged + simpler signatures

    /** 按 indicator 时序倒序 (最新快照在最前). */
    List<IndicatorVersion> findByIndicatorIdOrderByComputedAtDesc(String indicatorId);

    /** 分页历史 — Canvas Tab 版本管理列表用. */
    Page<IndicatorVersion> findByIndicatorIdOrderByComputedAtDesc(String indicatorId, Pageable pageable);

    /** 指定时间窗内的快照 — 趋势图用 (computedAt-window 不同于 D4 的 periodStart/End). */
    @Query("SELECT v FROM IndicatorVersion v " +
            "WHERE v.indicatorId = :indicatorId " +
            "AND v.computedAt BETWEEN :from AND :to " +
            "ORDER BY v.computedAt ASC")
    List<IndicatorVersion> findInWindow(
            @Param("indicatorId") String indicatorId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /** 最新一条 (current value) — Canvas summary card. */
    Optional<IndicatorVersion> findFirstByIndicatorIdOrderByComputedAtDesc(String indicatorId);
}
