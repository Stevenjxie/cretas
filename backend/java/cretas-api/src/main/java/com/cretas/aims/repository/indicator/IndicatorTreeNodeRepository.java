package com.cretas.aims.repository.indicator;

import com.cretas.aims.entity.indicator.IndicatorTreeNode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 指标树节点 Repository — 父子关系 / 物化路径 / DAG visualization 用。
 */
@Repository
public interface IndicatorTreeNodeRepository extends JpaRepository<IndicatorTreeNode, String> {

    /** 工厂全部节点 (DAG 全量构建)。 */
    List<IndicatorTreeNode> findByFactoryId(String factoryId);

    /** 某指标节点 (一个指标可能在不同上下文挂多个父级)。 */
    List<IndicatorTreeNode> findByIndicatorId(String indicatorId);

    /** 子节点 (用于 forward dependency walk)。 */
    List<IndicatorTreeNode> findByParentId(String parentId);

    /** Root 节点 (parent_id IS NULL)。 */
    @Query("SELECT n FROM IndicatorTreeNode n " +
            "WHERE n.factoryId = :factoryId AND n.parentId IS NULL")
    List<IndicatorTreeNode> findRoots(@Param("factoryId") String factoryId);
}
