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
 * <p>每次重算落库一行 (period_start / period_end / value / alert_level).
 * 用于趋势图 + 月报 + 合规审计 + version rollback (取过去某 snapshot 作 baseline).
 */
@Repository
public interface IndicatorVersionRepository extends JpaRepository<IndicatorVersion, String> {

    /** 按 indicator 时序倒序 (最新快照在最前)。 */
    List<IndicatorVersion> findByIndicatorIdOrderByComputedAtDesc(String indicatorId);

    /** 分页历史 — Canvas Tab 版本管理列表用。 */
    Page<IndicatorVersion> findByIndicatorIdOrderByComputedAtDesc(String indicatorId, Pageable pageable);

    /** 指定时间窗内的快照 — 趋势图用。 */
    @Query("SELECT v FROM IndicatorVersion v " +
            "WHERE v.indicatorId = :indicatorId " +
            "AND v.computedAt BETWEEN :from AND :to " +
            "ORDER BY v.computedAt ASC")
    List<IndicatorVersion> findInWindow(
            @Param("indicatorId") String indicatorId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /** 最新一条 (current value)。 */
    Optional<IndicatorVersion> findFirstByIndicatorIdOrderByComputedAtDesc(String indicatorId);

    /**
     * Versions whose business window {@code [period_start, period_end]} overlaps the
     * given window. Added 2026-05-22 to support
     * {@link com.cretas.aims.controller.IndicatorController}'s {@code /versions} endpoint
     * (cherry-pick fallout from PR #200). Overlap = NOT (a.end &lt; b.start OR a.start &gt; b.end).
     */
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

    /**
     * Recent versions for a factory across all indicators (DESC by computedAt).
     * Added 2026-05-22 to support {@link com.cretas.aims.controller.IndicatorController}'s
     * fallback non-windowed history fetch (cherry-pick fallout from PR #200).
     */
    @Query("SELECT v FROM IndicatorVersion v " +
            "WHERE v.factoryId = :factoryId " +
            "ORDER BY v.computedAt DESC")
    List<IndicatorVersion> findRecentByFactoryId(@Param("factoryId") String factoryId);
}
