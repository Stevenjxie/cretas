package com.cretas.aims.repository.indicator;

import com.cretas.aims.entity.indicator.Indicator;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 指标定义数据访问接口 — Indicator Center.
 *
 * <p>合并自 2026-05-22:
 * <ul>
 *   <li>Phase 1 Sprint 1 Day 3 (PR #153 reconcile): findByCode/AndDeletedAtIsNull/
 *       findActiveByFactoryId 等 + soft-delete-aware methods (Indicator Service 层使用)</li>
 *   <li>Canvas Phase A (PR #181 sister chat): findByFactoryIdAndIsActiveTrueOrderBy*
 *       + findAllByIsActiveTrue + findDistinctCategories (Canvas UI list 视图使用)</li>
 * </ul>
 *
 * <h3>功能分类</h3>
 * <ol>
 *   <li><b>软删除-aware 查询</b>：findByCode/...AndDeletedAtIsNull (Service 层入口)</li>
 *   <li><b>排序-aware 查询</b>：findBy*OrderByDisplayOrderAscNameAsc (Canvas UI list)</li>
 *   <li><b>唯一编码查询</b>：findByCode (2 个签名共存 — soft-delete-aware + Canvas-aware)</li>
 *   <li><b>分类查询</b>：findDistinctCategories 给 Canvas filter dropdown 用</li>
 *   <li><b>工厂隔离</b>：所有查询基于 factoryId 保证多租户隔离</li>
 * </ol>
 *
 * <h3>软删除语义</h3>
 * <p>Entity 已通过 {@code @Where(clause="deleted_at IS NULL")} 自动过滤软删除。
 * 显式 {@code AndDeletedAtIsNull} 仅作 belt-and-suspenders, 不影响 Canvas Phase A
 * 方法 (Entity-level filter 已生效).
 *
 * @since 2026-05-20 (Phase 1 D3) / 2026-05-21 (Canvas Phase A merge)
 * @see Indicator
 */
@Repository
public interface IndicatorRepository extends JpaRepository<Indicator, String> {

    // ============================================================
    // Phase 1 Day 3 — Service-layer (IndicatorQueryServiceImpl) 入口
    // ============================================================

    /**
     * 根据 (code, factoryId) 唯一查找 — Service.computeForCode 主入口。
     */
    Optional<Indicator> findByCodeAndFactoryIdAndDeletedAtIsNull(String code, String factoryId);

    /**
     * 查找工厂指定分类的全部启用指标 — Phase 1 Service 层 + REST list?category= 入口。
     */
    List<Indicator> findByFactoryIdAndCategoryAndIsActiveTrueAndDeletedAtIsNull(
            String factoryId, String category);

    /**
     * 查找工厂全部启用指标 (不限分类), 按 displayOrder 排序 — Service 层 list 入口。
     */
    @Query("SELECT i FROM Indicator i WHERE i.factoryId = :factoryId " +
           "AND i.isActive = true " +
           "ORDER BY i.displayOrder ASC NULLS LAST, i.code ASC")
    List<Indicator> findActiveByFactoryId(@Param("factoryId") String factoryId);

    /**
     * 按 (id, factoryId) 查找 — 多租户安全 detail 入口。
     */
    Optional<Indicator> findByIdAndFactoryIdAndDeletedAtIsNull(String id, String factoryId);

    /**
     * 检查 (factoryId, code) dup — 创建前。
     */
    boolean existsByFactoryIdAndCodeAndDeletedAtIsNull(String factoryId, String code);

    // ============================================================
    // Canvas Phase A — UI list 视图 (PR #181 sister chat) 入口
    // ============================================================

    /**
     * 工厂全部启用指标 (含全局 factoryId=GLOBAL — Canvas list 视图)。
     * Canvas UI 按 displayOrder + name 排序展示。
     */
    List<Indicator> findByFactoryIdAndIsActiveTrueOrderByDisplayOrderAscNameAsc(String factoryId);

    /**
     * 按分类筛选 + 排序 (Canvas UI filter dropdown 入口)。
     */
    List<Indicator> findByFactoryIdAndCategoryAndIsActiveTrueOrderByDisplayOrderAscNameAsc(
            String factoryId, String category);

    /**
     * 按 (factoryId, code) 查找 — Canvas-aware 简化签名 (无 soft-delete 显式过滤)。
     * 注: Entity {@code @Where} 已过滤软删除, 行为等同 findByCodeAndFactoryIdAndDeletedAtIsNull。
     */
    Optional<Indicator> findByFactoryIdAndCode(String factoryId, String code);

    /**
     * 全工厂启用指标 — Canvas admin 视角 (跨工厂概览)。
     */
    List<Indicator> findAllByIsActiveTrue();

    /**
     * 列出某工厂全部分类 (DISTINCT) — Canvas filter dropdown 入口。
     */
    @Query("SELECT DISTINCT i.category FROM Indicator i " +
            "WHERE i.factoryId = :factoryId AND i.isActive = TRUE " +
            "ORDER BY i.category")
    List<String> findDistinctCategories(@Param("factoryId") String factoryId);
}
