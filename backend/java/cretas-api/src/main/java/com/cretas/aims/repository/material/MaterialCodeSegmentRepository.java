package com.cretas.aims.repository.material;

import com.cretas.aims.entity.material.MaterialCodeSegment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;

/**
 * SP8: 物料分段编码字典 Repository.
 */
@Repository
public interface MaterialCodeSegmentRepository extends JpaRepository<MaterialCodeSegment, Long> {

    /** 按工厂和层级查询 (软删除过滤由 @Where 处理). */
    List<MaterialCodeSegment> findByFactoryIdAndLevelOrderBySortOrderAscSegmentCodeAsc(
            String factoryId, Short level);

    /** 按工厂查询所有节点 (树形拼装用). */
    List<MaterialCodeSegment> findByFactoryIdOrderBySortOrderAscSegmentCodeAsc(String factoryId);

    /** 按工厂+编码查找 (唯一). */
    Optional<MaterialCodeSegment> findByFactoryIdAndSegmentCode(String factoryId, String segmentCode);

    /** Serializes 16-digit suffix allocation for a selected L3 node. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM MaterialCodeSegment s WHERE s.factoryId = :factoryId " +
           "AND s.segmentCode = :segmentCode")
    Optional<MaterialCodeSegment> lockByFactoryIdAndSegmentCode(
            @Param("factoryId") String factoryId,
            @Param("segmentCode") String segmentCode);

    /**
     * ⚠️ 这个方法**看不见软删除的行** —— 实体上有 {@code @Where(deleted_at IS NULL)},
     * 它作用于所有 JPA 派生查询, 包括本方法。原注释写的「含软删除」是错的,
     * 2026-08-06 客户因此撞到一个假报错(见 {@link #existsBySegmentCodeIncludingDeleted})。
     */
    boolean existsByFactoryIdAndSegmentCode(String factoryId, String segmentCode);

    /**
     * 真·含软删除的编码占用检查。
     *
     * 🔴 为什么必须绕开 {@code @Where}: 唯一约束 {@code uk_mcs_factory_segment}
     * 是 {@code (factory_id, segment_code)} 且**不带** {@code WHERE deleted_at IS NULL}
     * —— 软删除的行**照样占着编码**(它必须占着: {@code material_business_code_prefixes}
     * 有外键指向 {@code (factory_id, segment_code)})。
     *
     * ⛔ 2026-08-06 事故: 六膳门把 L2 {@code 001001} 连同 30 个 L3 全软删了, 前端按
     * **活着的**子节点算 max+1 得到 {@code 0010010001}, 而那个编码正被一条软删行占着 →
     * INSERT 撞唯一约束 → 被 catch 成「同一父级下已存在同名分类」。
     * 于是提示让用户「改个名字」, 而**改名字永远没用**, 冲突的是编码不是名字。
     *
     * 用 native query: {@code @Where} 只作用于 HQL/派生查询, 原生 SQL 不受它影响。
     */
    @Query(value = "SELECT EXISTS(SELECT 1 FROM material_code_segments "
            + "WHERE factory_id = :factoryId AND segment_code = :segmentCode)",
           nativeQuery = true)
    boolean existsBySegmentCodeIncludingDeleted(
            @Param("factoryId") String factoryId,
            @Param("segmentCode") String segmentCode);

    /**
     * 某父级下**所有**子节点编码(含软删除), 用于分配下一个可用编码。
     *
     * 分配口径必须与唯一约束口径一致 —— 只看活着的行就会分到一个被软删行占用的编码。
     * level=1 没有父级, 用 {@code parentCode IS NULL} 命中。
     *
     * ⚠️ {@code :parentCode} 必须显式 CAST: 裸的 {@code ? IS NULL} 让 PostgreSQL
     * 推不出参数类型, 直接报 {@code could not determine data type of parameter $2}
     * (2026-08-06 上线后实测 500)。Mock 掉仓储的单测**跑不到真 SQL**, 只有打真库才会红。
     */
    @Query(value = "SELECT segment_code FROM material_code_segments "
            + "WHERE factory_id = :factoryId "
            + "AND (CAST(:parentCode AS text) IS NULL AND parent_code IS NULL "
            + "     OR parent_code = CAST(:parentCode AS text))",
           nativeQuery = true)
    List<String> findSegmentCodesByParentIncludingDeleted(
            @Param("factoryId") String factoryId,
            @Param("parentCode") String parentCode);

    /**
     * 已删除(软删)的分类 —— 给「显示已删除 + 恢复」用。
     *
     * 🔴 必须绕开实体上的 {@code @Where(deleted_at IS NULL)}, 否则永远返回空。
     * 这也是为什么以前界面上看不到它们: 所有 JPA 路径都被那条 @Where 挡住了。
     */
    @Query(value = "SELECT * FROM material_code_segments "
            + "WHERE factory_id = :factoryId AND deleted_at IS NOT NULL "
            + "ORDER BY level, segment_code",
           nativeQuery = true)
    List<MaterialCodeSegment> findDeletedByFactoryId(@Param("factoryId") String factoryId);

    /** 按 id 取一条(含软删除) —— 恢复时要先把那条已删的行读出来。 */
    @Query(value = "SELECT * FROM material_code_segments WHERE id = :id", nativeQuery = true)
    Optional<MaterialCodeSegment> findByIdIncludingDeleted(@Param("id") Long id);

    /** 恢复: 只清 deleted_at, 不动其它字段(编码/名称/归属都按删除前原样回来)。 */
    @Modifying
    @Query(value = "UPDATE material_code_segments SET deleted_at = NULL, updated_at = now() "
            + "WHERE id = :id AND deleted_at IS NOT NULL",
           nativeQuery = true)
    int restoreById(@Param("id") Long id);

    /** 该分类下还有几个**活着的**直接子分类 —— 删除守卫用。 */
    long countByFactoryIdAndParentCode(String factoryId, String parentCode);

    /** 取一条编码对应的行(含软删除), 用于把「编码被占」说清楚是被谁占的。 */
    @Query(value = "SELECT segment_label FROM material_code_segments "
            + "WHERE factory_id = :factoryId AND segment_code = :segmentCode LIMIT 1",
           nativeQuery = true)
    Optional<String> findLabelBySegmentCodeIncludingDeleted(
            @Param("factoryId") String factoryId,
            @Param("segmentCode") String segmentCode);

    boolean existsByFactoryIdAndLevelAndParentCodeAndNormalizedLabelAndIdNot(
            String factoryId,
            Short level,
            String parentCode,
            String normalizedLabel,
            Long excludeId);

    /** 按工厂+父编码查询子节点. */
    List<MaterialCodeSegment> findByFactoryIdAndParentCodeOrderBySortOrderAscSegmentCodeAsc(
            String factoryId, String parentCode);

    /** 统计该工厂某层级的节点数 (判断是否已配置分段字典). */
    long countByFactoryIdAndLevel(String factoryId, Short level);

    /**
     * 查询该工厂中以 segmentCode 为前缀的所有 L3 品名节点.
     * 用于16位编码生成器检索同前缀已有编码序号.
     */
    @Query("SELECT s.segmentCode FROM MaterialCodeSegment s " +
           "WHERE s.factoryId = :factoryId AND s.level = 3 " +
           "AND s.segmentCode LIKE CONCAT(:prefix, '%')")
    List<String> findL3SegmentCodesByFactoryIdAndPrefix(
            @Param("factoryId") String factoryId,
            @Param("prefix") String prefix);
}
