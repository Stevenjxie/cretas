package com.cretas.aims.repository.indicator;

import com.cretas.aims.entity.indicator.IndicatorTreeNode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 指标树节点数据访问接口 (Phase 1 Sprint 1 Day 3, 2026-05-20)
 *
 * <p>承载指标的层级关系 (parent_id) + 权重 + 物化路径 (materialized_path)。
 * 同一指标可能在不同上下文有不同父级 — 因此节点跟指标是 N:1 关系, parent_id NULL 表示根节点。
 *
 * <h3>功能分类</h3>
 * <ol>
 *   <li><b>子节点查询</b>：按 parentIndicatorId 列出直接子节点</li>
 *   <li><b>根节点查询</b>：parent_id IS NULL 的工厂根指标</li>
 *   <li><b>整树查询</b>：按 materializedPath 前缀匹配整个子树 (待 Day 4 Service 层封装)</li>
 * </ol>
 *
 * <h3>软删除语义</h3>
 * <p>Entity 层已通过 {@code @Where(clause="deleted_at IS NULL")} 自动过滤软删除记录。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-05-20
 * @see IndicatorTreeNode 实体类
 */
@Repository
public interface IndicatorTreeNodeRepository extends JpaRepository<IndicatorTreeNode, String> {

    /**
     * 按父指标 ID + 工厂查找全部直接子节点 (depth+1)。
     * 返回 displayOrder 升序便于前端按声明顺序渲染。
     *
     * @param parentIndicatorId  父指标 ID (parent_id 列)
     * @param factoryId          工厂ID
     * @return 子节点列表
     */
    @Query("SELECT n FROM IndicatorTreeNode n " +
           "WHERE n.parentId = :parentIndicatorId " +
           "AND n.factoryId = :factoryId " +
           "ORDER BY n.displayOrder ASC NULLS LAST, n.id ASC")
    List<IndicatorTreeNode> findByParentIndicatorIdAndFactoryIdAndDeletedAtIsNull(
            @Param("parentIndicatorId") String parentIndicatorId,
            @Param("factoryId") String factoryId);

    /**
     * 查询工厂全部根节点 (parent_id IS NULL)。
     * 用途：前端指标中心首页按根节点展开树。
     *
     * @param factoryId  工厂ID
     * @return 根节点列表
     */
    @Query("SELECT n FROM IndicatorTreeNode n " +
           "WHERE n.factoryId = :factoryId " +
           "AND n.parentId IS NULL " +
           "ORDER BY n.displayOrder ASC NULLS LAST, n.id ASC")
    List<IndicatorTreeNode> findRootsByFactoryId(@Param("factoryId") String factoryId);

    /**
     * 按 indicator_id 列出该指标的所有节点 (可能跨多个父级上下文)。
     *
     * @param indicatorId  指标 ID
     * @param factoryId    工厂ID
     * @return 节点列表
     */
    List<IndicatorTreeNode> findByIndicatorIdAndFactoryId(String indicatorId, String factoryId);

    /**
     * 按 materializedPath 前缀匹配子树 (LIKE 'root/parent/%')。
     * 例 path = "root-id/yield-rate", 查询 "root-id/yield-rate/%" 返回全部后代节点。
     *
     * @param factoryId   工厂ID
     * @param pathPrefix  路径前缀 (不含末尾 %)
     * @return 后代节点列表 (按 depth ASC, displayOrder ASC)
     */
    @Query("SELECT n FROM IndicatorTreeNode n " +
           "WHERE n.factoryId = :factoryId " +
           "AND n.materializedPath LIKE CONCAT(:pathPrefix, '/%') " +
           "ORDER BY n.depth ASC, n.displayOrder ASC NULLS LAST")
    List<IndicatorTreeNode> findSubtreeByPath(@Param("factoryId") String factoryId,
                                              @Param("pathPrefix") String pathPrefix);

    /**
     * 按 ID + factory 查找 (多租户安全)。
     */
    Optional<IndicatorTreeNode> findByIdAndFactoryId(String id, String factoryId);
}
