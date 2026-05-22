package com.cretas.aims.repository.lineage;

import com.cretas.aims.entity.lineage.BatchLineageClosure;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 批次 lineage 闭包 Repository — O(1) 祖先/后代查询。
 * <p>由 fn_maintain_lineage_closure 触发器 自动维护，不在应用层手动写入。
 */
@Repository
public interface BatchLineageClosureRepository extends JpaRepository<BatchLineageClosure, String> {

    /** 某批次的全部祖先 (含自引用 depth=0)。 */
    @Query("SELECT c FROM BatchLineageClosure c " +
            "WHERE c.factoryId = :factoryId " +
            "AND c.descendantId = :batchId AND c.descendantType = :batchType " +
            "ORDER BY c.depth ASC")
    List<BatchLineageClosure> findAncestors(
            @Param("factoryId") String factoryId,
            @Param("batchId") String batchId,
            @Param("batchType") String batchType);

    /** 某批次的全部后代。 */
    @Query("SELECT c FROM BatchLineageClosure c " +
            "WHERE c.factoryId = :factoryId " +
            "AND c.ancestorId = :batchId AND c.ancestorType = :batchType " +
            "ORDER BY c.depth ASC")
    List<BatchLineageClosure> findDescendants(
            @Param("factoryId") String factoryId,
            @Param("batchId") String batchId,
            @Param("batchType") String batchType);

    /** 完整链路 (祖先 + 后代)。 */
    @Query("SELECT c FROM BatchLineageClosure c " +
            "WHERE c.factoryId = :factoryId " +
            "AND ((c.descendantId = :batchId AND c.descendantType = :batchType) " +
            "  OR (c.ancestorId = :batchId AND c.ancestorType = :batchType)) " +
            "AND c.depth <= :maxDepth " +
            "ORDER BY c.depth ASC")
    List<BatchLineageClosure> findFullGraph(
            @Param("factoryId") String factoryId,
            @Param("batchId") String batchId,
            @Param("batchType") String batchType,
            @Param("maxDepth") Integer maxDepth);
}
