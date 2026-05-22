package com.cretas.aims.repository.indicator;

import com.cretas.aims.entity.indicator.IndicatorComputation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 指标计算策略数据访问接口 (Phase 1 Sprint 1 Day 3, 2026-05-20)
 *
 * <p>把指标连接到实际计算源 (SpEL 公式 / Python 端点 / JPA query)。
 * 同一指标允许多条策略 (按 priority 排序, 主备 fallback)。
 *
 * <h3>功能分类</h3>
 * <ol>
 *   <li><b>指标策略查询</b>：按 indicatorId 获取启用的全部策略</li>
 *   <li><b>主备 fallback</b>：priority ASC 排序, 主策略失败可降级备用</li>
 * </ol>
 *
 * <h3>软删除语义</h3>
 * <p>Entity 层已通过 {@code @Where(clause="deleted_at IS NULL")} 自动过滤软删除记录。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-05-20
 * @see IndicatorComputation 实体类
 */
@Repository
public interface IndicatorComputationRepository extends JpaRepository<IndicatorComputation, String> {

    /**
     * 查询指标全部启用计算策略, 按优先级升序 (主策略在前)。
     * Day 4 IndicatorQueryServiceImpl 主备 fallback 用。
     *
     * @param indicatorId  指标 ID
     * @return 启用策略列表 (priority ASC)
     */
    @Query("SELECT c FROM IndicatorComputation c " +
           "WHERE c.indicatorId = :indicatorId " +
           "AND c.isActive = true " +
           "ORDER BY c.priority ASC")
    List<IndicatorComputation> findByIndicatorIdAndIsActiveTrueAndDeletedAtIsNull(
            @Param("indicatorId") String indicatorId);

    /**
     * 列出某 computeType 的全部启用策略 (例如查所有走 PYTHON_ENDPOINT 的)。
     *
     * @param computeType  类型 (FORMULA / PYTHON_ENDPOINT / JPA_QUERY)
     * @return 启用策略列表
     */
    @Query("SELECT c FROM IndicatorComputation c " +
           "WHERE c.computeType = :computeType " +
           "AND c.isActive = true")
    List<IndicatorComputation> findActiveByComputeType(@Param("computeType") String computeType);

    /**
     * 取指定指标的主策略 (priority 最低的启用策略)。
     *
     * @param indicatorId  指标 ID
     * @return 主策略 Optional
     */
    @Query("SELECT c FROM IndicatorComputation c " +
           "WHERE c.indicatorId = :indicatorId " +
           "AND c.isActive = true " +
           "ORDER BY c.priority ASC")
    List<IndicatorComputation> findPrimaryByIndicatorId(@Param("indicatorId") String indicatorId);

    /**
     * 单条获取 by id (no factory scope on this entity — 按 indicator 走 factory 隔离)。
     */
    Optional<IndicatorComputation> findByIdAndDeletedAtIsNull(String id);
}
