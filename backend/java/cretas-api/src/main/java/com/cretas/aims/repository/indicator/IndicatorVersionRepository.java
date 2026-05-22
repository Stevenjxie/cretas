package com.cretas.aims.repository.indicator;

import com.cretas.aims.entity.indicator.IndicatorVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * 指标历史快照数据访问接口 (Phase 1 Sprint 1 Day 4 添加, 2026-05-22)
 *
 * <p>Day 3 漏建; Day 4 IndicatorQueryServiceImpl 的 {@code saveVersion} 流程需要它。
 * append-only 审计实体, 实体侧已通过 {@code @PreRemove} 阻止物理删除。
 *
 * <h3>功能</h3>
 * <ol>
 *   <li><b>persist</b>：每次重算落库一行 (Service 层调用 {@code save})</li>
 *   <li><b>按指标 + 时间窗查询历史</b>：用于趋势图 / 月报</li>
 * </ol>
 *
 * @author Cretas Team
 * @since 2026-05-22 (Phase 1 Sprint 1 Day 4)
 * @see IndicatorVersion 实体类
 */
@Repository
public interface IndicatorVersionRepository extends JpaRepository<IndicatorVersion, String> {

    /**
     * 按指标 + 业务时间窗查询历史快照 (computed_at 降序，最新在前).
     *
     * @param indicatorId 指标 ID
     * @param factoryId   工厂 ID
     * @param windowStart 时间窗起 (period_end 不小于此)
     * @param windowEnd   时间窗止 (period_start 不大于此)
     * @return 重叠时间窗的版本列表
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
     * 工厂全部指标的最新一次快照 (Day 5 dashboard 可能用).
     *
     * @param factoryId 工厂 ID
     * @return 最近 N 条快照, computedAt 降序
     */
    @Query("SELECT v FROM IndicatorVersion v " +
           "WHERE v.factoryId = :factoryId " +
           "ORDER BY v.computedAt DESC")
    List<IndicatorVersion> findRecentByFactoryId(@Param("factoryId") String factoryId);
}
