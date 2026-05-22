package com.cretas.aims.repository.lineage;

import com.cretas.aims.entity.lineage.BatchLineageClosure;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 批次 lineage 闭包数据访问接口 (Phase 1 Sprint 1 Day 3, 2026-05-20)
 *
 * <p>物化的传递闭包, 提供 O(1) 祖先/后代查询。
 * 由触发器 {@code fn_maintain_lineage_closure} 在
 * {@link com.cretas.aims.entity.lineage.BatchLineageEdge} INSERT 时自动维护。
 *
 * <h3>闭包语义</h3>
 * <ul>
 *   <li><b>depth = 0</b>：自引用 (source/target 各一条 self-edge)</li>
 *   <li><b>depth = 1</b>：直接 edge</li>
 *   <li><b>depth &gt; 1</b>：多跳传递闭包</li>
 * </ul>
 *
 * <h3>功能分类</h3>
 * <ol>
 *   <li><b>findAncestors</b>：查询某 descendant 的所有祖先 (上游溯源, "成品来自哪")</li>
 *   <li><b>findDescendants</b>：查询某 ancestor 的所有后代 (下游去向, "原料去哪了")</li>
 *   <li><b>findFullGraph</b>：带 maxDepth 限制的双向遍历 (整图, 用于客户投诉追溯)</li>
 * </ol>
 *
 * <h3>Append-only forensic 设计</h3>
 * <p>Entity 层没有 {@code @Where(deleted_at IS NULL)} — closure 派生自 edges,
 * forensic 追溯必须保证完整链路。同时 entity 层 @PreRemove 阻止物理删除。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-05-20
 * @see BatchLineageClosure 实体类
 */
@Repository
public interface BatchLineageClosureRepository extends JpaRepository<BatchLineageClosure, String> {

    /**
     * 查询某 descendant 节点的所有祖先 (depth&gt;0, 不含 self-edge), 按 depth 升序。
     * 用途：客户投诉成品 → 一路追溯到原料 → 供应商 → CCP。
     *
     * @param factoryId       工厂ID
     * @param descendantId    后代节点 ID
     * @param descendantType  后代节点类型
     * @return 祖先闭包记录列表 (depth ASC)
     */
    @Query("SELECT c FROM BatchLineageClosure c " +
           "WHERE c.factoryId = :factoryId " +
           "AND c.descendantId = :descendantId " +
           "AND c.descendantType = :descendantType " +
           "AND c.depth > 0 " +
           "ORDER BY c.depth ASC")
    List<BatchLineageClosure> findAncestors(@Param("factoryId") String factoryId,
                                             @Param("descendantId") String descendantId,
                                             @Param("descendantType") String descendantType);

    /**
     * 查询某 ancestor 节点的所有后代 (depth&gt;0, 不含 self-edge), 按 depth 升序。
     * 用途：原料批次 → 一路找出影响到哪些成品 → 哪些发货 → 哪些客户。
     *
     * @param factoryId     工厂ID
     * @param ancestorId    祖先节点 ID
     * @param ancestorType  祖先节点类型
     * @return 后代闭包记录列表 (depth ASC)
     */
    @Query("SELECT c FROM BatchLineageClosure c " +
           "WHERE c.factoryId = :factoryId " +
           "AND c.ancestorId = :ancestorId " +
           "AND c.ancestorType = :ancestorType " +
           "AND c.depth > 0 " +
           "ORDER BY c.depth ASC")
    List<BatchLineageClosure> findDescendants(@Param("factoryId") String factoryId,
                                               @Param("ancestorId") String ancestorId,
                                               @Param("ancestorType") String ancestorType);

    /**
     * 双向查询某节点的整图 (祖先+后代+自引用), depth&lt;=maxDepth, 用于客户投诉追溯界面。
     *
     * @param factoryId   工厂ID
     * @param nodeId      节点 ID
     * @param nodeType    节点类型
     * @param maxDepth    最大深度 (含)
     * @return 闭包记录列表 (该节点作 ancestor 或 descendant, depth ASC)
     */
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

    /**
     * 工厂全部 closure 记录计数 — 监控触发器健康度 (closure 行数 >> edges 行数说明工作正常)。
     */
    long countByFactoryId(String factoryId);
}
