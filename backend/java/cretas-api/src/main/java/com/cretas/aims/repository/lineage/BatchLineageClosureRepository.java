package com.cretas.aims.repository.lineage;

import com.cretas.aims.entity.lineage.BatchLineageClosure;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 批次 lineage 闭包 Repository — O(1) 祖先/后代查询.
 *
 * <p>由 {@code fn_maintain_lineage_closure} 触发器在 edge INSERT 时自动维护.
 *
 * <p>合并自 Phase 1 D3 + Canvas Phase A (2026-05-22) — JPQL 完全一致, 仅参数名
 * 不同 (我的 descendantId 等 vs sister 的 batchId). 用 mine 因 Day 6 LineageService
 * 依赖. countByFactoryId 加入 (D3 监控用).
 *
 * <h3>闭包语义</h3>
 * <ul>
 *   <li>depth=0: self-edge</li>
 *   <li>depth=1: 直接 edge</li>
 *   <li>depth>1: 多跳传递闭包</li>
 * </ul>
 */
@Repository
public interface BatchLineageClosureRepository extends JpaRepository<BatchLineageClosure, String> {

    /** 某 descendant 的全部祖先 (depth>0). */
    @Query("SELECT c FROM BatchLineageClosure c " +
           "WHERE c.factoryId = :factoryId " +
           "AND c.descendantId = :descendantId " +
           "AND c.descendantType = :descendantType " +
           "AND c.depth > 0 " +
           "ORDER BY c.depth ASC")
    List<BatchLineageClosure> findAncestors(@Param("factoryId") String factoryId,
                                             @Param("descendantId") String descendantId,
                                             @Param("descendantType") String descendantType);

    /** 某 ancestor 的全部后代 (depth>0). */
    @Query("SELECT c FROM BatchLineageClosure c " +
           "WHERE c.factoryId = :factoryId " +
           "AND c.ancestorId = :ancestorId " +
           "AND c.ancestorType = :ancestorType " +
           "AND c.depth > 0 " +
           "ORDER BY c.depth ASC")
    List<BatchLineageClosure> findDescendants(@Param("factoryId") String factoryId,
                                               @Param("ancestorId") String ancestorId,
                                               @Param("ancestorType") String ancestorType);

    /** 双向查询整图 (祖先+后代+自引用), depth<=maxDepth. */
    @Query("SELECT c FROM BatchLineageClosure c " +
           "WHERE c.factoryId = :factoryId " +
           "AND c.depth <= :maxDepth " +
           "AND ((c.ancestorId = :nodeId AND c.ancestorType = :nodeType) " +
           "  OR (c.descendantId = :nodeId AND c.descendantType = :nodeType)) " +
           "ORDER BY c.depth ASC")
    List<BatchLineageClosure> findFullGraph(@Param("factoryId") String factoryId,
                                             @Param("nodeId") String nodeId,
                                             @Param("nodeType") String nodeType,
                                             @Param("maxDepth") Integer maxDepth);

    /** 工厂全部 closure 记录计数 — 监控触发器健康度. */
    long countByFactoryId(String factoryId);
}
