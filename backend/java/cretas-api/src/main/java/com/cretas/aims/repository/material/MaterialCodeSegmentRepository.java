package com.cretas.aims.repository.material;

import com.cretas.aims.entity.material.MaterialCodeSegment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** 物料分类树 Repository。 */
@Repository
public interface MaterialCodeSegmentRepository extends JpaRepository<MaterialCodeSegment, Long> {

    /** 按工厂和层级查询 (软删除过滤由 @Where 处理). */
    List<MaterialCodeSegment> findByFactoryIdAndLevelOrderBySortOrderAscIdAsc(
            String factoryId, Short level);

    /** 按工厂查询所有节点 (树形拼装用). */
    List<MaterialCodeSegment> findByFactoryIdOrderBySortOrderAscIdAsc(String factoryId);

    Optional<MaterialCodeSegment> findByIdAndFactoryId(Long id, String factoryId);

    /**
     * 已删除(软删)的分类 —— 给「显示已删除 + 恢复」用。
     *
     * 🔴 必须绕开实体上的 {@code @Where(deleted_at IS NULL)}, 否则永远返回空。
     * 这也是为什么以前界面上看不到它们: 所有 JPA 路径都被那条 @Where 挡住了。
     */
    @Query(value = "SELECT * FROM material_code_segments "
            + "WHERE factory_id = :factoryId AND deleted_at IS NOT NULL "
            + "ORDER BY level, id",
           nativeQuery = true)
    List<MaterialCodeSegment> findDeletedByFactoryId(@Param("factoryId") String factoryId);

    /** 按 id 取一条(含软删除) —— 恢复时要先把那条已删的行读出来。 */
    @Query(value = "SELECT * FROM material_code_segments WHERE id = :id", nativeQuery = true)
    Optional<MaterialCodeSegment> findByIdIncludingDeleted(@Param("id") Long id);

    /** 恢复: 只清 deleted_at, 不动名称/归属。 */
    @Modifying
    @Query(value = "UPDATE material_code_segments SET deleted_at = NULL, updated_at = now() "
            + "WHERE id = :id AND deleted_at IS NOT NULL",
           nativeQuery = true)
    int restoreById(@Param("id") Long id);

    /** 该分类下还有几个**活着的**直接子分类 —— 删除守卫用。 */
    long countByFactoryIdAndParentId(String factoryId, Long parentId);

    @Query("SELECT CASE WHEN COUNT(s) > 0 THEN true ELSE false END FROM MaterialCodeSegment s "
            + "WHERE s.factoryId = :factoryId AND s.level = :level "
            + "AND ((:parentId IS NULL AND s.parentId IS NULL) OR s.parentId = :parentId) "
            + "AND s.normalizedLabel = :normalizedLabel AND s.id <> :excludeId")
    boolean existsSiblingWithNormalizedLabel(
            @Param("factoryId") String factoryId,
            @Param("level") Short level,
            @Param("parentId") Long parentId,
            @Param("normalizedLabel") String normalizedLabel,
            @Param("excludeId") Long excludeId);

    /** 按工厂+父编码查询子节点. */
    @Query("SELECT s FROM MaterialCodeSegment s WHERE s.factoryId = :factoryId "
            + "AND ((:parentId IS NULL AND s.parentId IS NULL) OR s.parentId = :parentId) "
            + "ORDER BY s.sortOrder, s.id")
    List<MaterialCodeSegment> findSiblings(
            @Param("factoryId") String factoryId,
            @Param("parentId") Long parentId);

    /** 统计该工厂某层级的节点数 (判断是否已配置分段字典). */
    long countByFactoryIdAndLevel(String factoryId, Short level);

}
