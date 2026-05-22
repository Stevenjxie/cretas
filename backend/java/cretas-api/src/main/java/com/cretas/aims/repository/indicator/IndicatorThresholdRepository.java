package com.cretas.aims.repository.indicator;

import com.cretas.aims.entity.indicator.IndicatorThreshold;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 指标阈值数据访问接口 (Phase 1 Sprint 1 Day 5 添加, 2026-05-22)
 *
 * <p>Day 3 漏建; Day 5 IndicatorController 的 {@code GET /thresholds/{code}} 端点需要它。
 * 阈值定义 GREEN/YELLOW/RED 三色预警规则; 多条 threshold 按 alert_level 组织。
 *
 * <h3>功能</h3>
 * <ol>
 *   <li><b>按指标 ID 列出全部启用阈值</b>: 用于前端配色 + 预警判断</li>
 *   <li><b>按级别查询</b>: 仅取某一级 (GREEN / YELLOW / RED) 阈值</li>
 * </ol>
 *
 * <h3>软删除语义</h3>
 * <p>Entity 层已通过 {@code @Where(clause="deleted_at IS NULL")} 自动过滤软删除记录。
 *
 * @author Cretas Team
 * @since 2026-05-22 (Phase 1 Sprint 1 Day 5)
 * @see IndicatorThreshold 实体类
 */
@Repository
public interface IndicatorThresholdRepository extends JpaRepository<IndicatorThreshold, String> {

    /**
     * 按指标 ID + factoryId 列出全部启用阈值, 按 alertLevel 排序 (GREEN/YELLOW/RED).
     *
     * @param indicatorId 指标 ID
     * @param factoryId   工厂 ID
     * @return 启用阈值列表
     */
    @Query("SELECT t FROM IndicatorThreshold t " +
           "WHERE t.indicatorId = :indicatorId " +
           "AND t.factoryId = :factoryId " +
           "AND t.isActive = true " +
           "ORDER BY t.alertLevel ASC")
    List<IndicatorThreshold> findActiveByIndicatorIdAndFactoryId(
            @Param("indicatorId") String indicatorId,
            @Param("factoryId") String factoryId);

    /**
     * 按指标 ID 列出全部启用阈值 (不含 factoryId 过滤, 内部使用).
     *
     * @param indicatorId 指标 ID
     * @return 启用阈值列表
     */
    @Query("SELECT t FROM IndicatorThreshold t " +
           "WHERE t.indicatorId = :indicatorId " +
           "AND t.isActive = true " +
           "ORDER BY t.alertLevel ASC")
    List<IndicatorThreshold> findActiveByIndicatorId(@Param("indicatorId") String indicatorId);
}
