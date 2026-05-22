package com.cretas.aims.repository.lineage;

import com.cretas.aims.entity.lineage.BatchLineageEdge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 批次溯源有向边数据访问接口 (Phase 1 Sprint 1 Day 3, 2026-05-20)
 *
 * <p>食品行业批次级 lineage 的核心载体。每条 edge 表示一次"加工/装配/出货/复用"事件,
 * 由 source -> target (多态: MATERIAL_BATCH / PRODUCTION_BATCH / FINISHED_BATCH /
 * SHIPMENT_RECORD)。
 *
 * <h3>Append-only forensic 设计</h3>
 * <p>Entity 层没有 {@code @Where(deleted_at IS NULL)} — lineage 一旦写入永不删除,
 * 即便相关批次软删, lineage 仍可追溯。同时 entity 层 @PreRemove 阻止物理删除。
 *
 * <h3>功能分类</h3>
 * <ol>
 *   <li><b>源批次查询</b>：findBy(source) — "这个原料/批次去了哪 (下游)"</li>
 *   <li><b>目标批次查询</b>：findBy(target) — "这个成品/出货来自哪 (上游)"</li>
 *   <li><b>时间窗</b>：按 eventTime 范围查找 (合规审计 / 月报)</li>
 * </ol>
 *
 * <h3>触发器副作用</h3>
 * <p>插入此表会触发 {@code fn_maintain_lineage_closure} 自动维护
 * {@link com.cretas.aims.entity.lineage.BatchLineageClosure} 表。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-05-20
 * @see BatchLineageEdge 实体类
 */
@Repository
public interface BatchLineageEdgeRepository extends JpaRepository<BatchLineageEdge, String> {

    /**
     * 查询某源批次的全部下游 edge (这个原料/批次去了哪)。
     *
     * @param factoryId   工厂ID
     * @param sourceId    源批次 ID (String, Long 类型批次以字符串形式存入)
     * @param sourceType  源批次类型 (MATERIAL_BATCH / PRODUCTION_BATCH / ...)
     * @return 下游 edges
     */
    List<BatchLineageEdge> findByFactoryIdAndSourceIdAndSourceType(
            String factoryId, String sourceId, String sourceType);

    /**
     * 查询某目标批次的全部上游 edge (这个成品/出货来自哪)。
     *
     * @param factoryId   工厂ID
     * @param targetId    目标批次 ID
     * @param targetType  目标批次类型
     * @return 上游 edges
     */
    List<BatchLineageEdge> findByFactoryIdAndTargetIdAndTargetType(
            String factoryId, String targetId, String targetType);

    /**
     * 按 edgeType + 时间窗查询 (合规审计场景, 例如 "近 30 天 REWORK 事件")。
     *
     * @param factoryId  工厂ID
     * @param edgeType   边类型 (RAW_TO_PRODUCTION / PRODUCTION_TO_FINISHED / FINISHED_TO_SHIPMENT / REWORK / BLEND)
     * @param fromTime   起始时间 (inclusive)
     * @param toTime     结束时间 (inclusive)
     * @return edges 列表 (eventTime ASC)
     */
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

    /**
     * 工厂全部 edges 计数 — 监控指标 dashboard 用。
     */
    long countByFactoryId(String factoryId);
}
