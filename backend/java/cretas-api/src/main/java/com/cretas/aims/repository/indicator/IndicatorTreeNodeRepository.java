package com.cretas.aims.repository.indicator;

import com.cretas.aims.entity.indicator.IndicatorTreeNode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 指标树节点 Repository — 合并自 Phase 1 D3 (PR #153) + Canvas Phase A (PR #181).
 *
 * <p>Service 层 (D4) 用 findByParentIndicatorId/findRootsByFactoryId/findSubtreeByPath.
 * Canvas UI (sister chat) 用 findByFactoryId/findByParentId/findRoots.
 */
@Repository
public interface IndicatorTreeNodeRepository extends JpaRepository<IndicatorTreeNode, String> {

    // Phase 1 D3 Service-layer

    @Query("SELECT n FROM IndicatorTreeNode n " +
           "WHERE n.parentId = :parentIndicatorId " +
           "AND n.factoryId = :factoryId " +
           "ORDER BY n.displayOrder ASC NULLS LAST, n.id ASC")
    List<IndicatorTreeNode> findByParentIndicatorIdAndFactoryIdAndDeletedAtIsNull(
            @Param("parentIndicatorId") String parentIndicatorId,
            @Param("factoryId") String factoryId);

    @Query("SELECT n FROM IndicatorTreeNode n " +
           "WHERE n.factoryId = :factoryId " +
           "AND n.parentId IS NULL " +
           "ORDER BY n.displayOrder ASC NULLS LAST, n.id ASC")
    List<IndicatorTreeNode> findRootsByFactoryId(@Param("factoryId") String factoryId);

    List<IndicatorTreeNode> findByIndicatorIdAndFactoryId(String indicatorId, String factoryId);

    @Query("SELECT n FROM IndicatorTreeNode n " +
           "WHERE n.factoryId = :factoryId " +
           "AND n.materializedPath LIKE CONCAT(:pathPrefix, '/%') " +
           "ORDER BY n.depth ASC, n.displayOrder ASC NULLS LAST")
    List<IndicatorTreeNode> findSubtreeByPath(@Param("factoryId") String factoryId,
                                              @Param("pathPrefix") String pathPrefix);

    Optional<IndicatorTreeNode> findByIdAndFactoryId(String id, String factoryId);

    // Canvas Phase A — DAG visualization

    List<IndicatorTreeNode> findByFactoryId(String factoryId);

    List<IndicatorTreeNode> findByIndicatorId(String indicatorId);

    List<IndicatorTreeNode> findByParentId(String parentId);

    @Query("SELECT n FROM IndicatorTreeNode n " +
            "WHERE n.factoryId = :factoryId AND n.parentId IS NULL")
    List<IndicatorTreeNode> findRoots(@Param("factoryId") String factoryId);
}
