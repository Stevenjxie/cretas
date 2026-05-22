package com.cretas.aims.repository.lineage;

import com.cretas.aims.entity.lineage.BatchLineageEdge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 批次溯源有向边 Repository — append-only forensic, 不删.
 *
 * <p>合并自 Phase 1 D3 + Canvas Phase A (2026-05-22):
 * <ul>
 *   <li>findByFactoryIdAndSourceIdAndSourceType / findByFactoryIdAndTargetIdAndTargetType — 两边共用</li>
 *   <li>findByEdgeTypeAndEventTimeBetween + countByFactoryId — D3 service layer 入口</li>
 *   <li>findByFactoryId — Canvas Phase A 全量入口</li>
 * </ul>
 */
@Repository
public interface BatchLineageEdgeRepository extends JpaRepository<BatchLineageEdge, String> {

    /** 查询某源批次的全部下游 edge (forward walk). */
    List<BatchLineageEdge> findByFactoryIdAndSourceIdAndSourceType(
            String factoryId, String sourceId, String sourceType);

    /** 查询某目标批次的全部上游 edge (backward walk). */
    List<BatchLineageEdge> findByFactoryIdAndTargetIdAndTargetType(
            String factoryId, String targetId, String targetType);

    /** 按 edgeType + 时间窗查询 (D3 service 合规审计场景). */
    @Query("SELECT e FROM BatchLineageEdge e " +
           "WHERE e.factoryId = :factoryId " +
           "AND e.edgeType = :edgeType " +
           "AND e.eventTime BETWEEN :fromTime AND :toTime " +
           "ORDER BY e.eventTime ASC")
    List<BatchLineageEdge> findByEdgeTypeAndEventTimeBetween(
            @Param("factoryId") String factoryId,
            @Param("edgeType") String edgeType,
            @Param("fromTime") LocalDateTime fromTime,
            @Param("toTime") LocalDateTime toTime);

    /** 工厂全部 edges 计数 — 监控指标 dashboard 用. */
    long countByFactoryId(String factoryId);

    /** Canvas Phase A 全量入口 — 跨多种 source/target 类型组成 DAG. */
    List<BatchLineageEdge> findByFactoryId(String factoryId);
}
