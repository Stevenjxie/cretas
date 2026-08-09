package com.cretas.aims.repository;

import com.cretas.aims.entity.RawMaterialType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
/**
 * 原材料类型数据访问接口
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Repository
public interface RawMaterialTypeRepository extends JpaRepository<RawMaterialType, String> {
    interface CodeConflictView {
        String getId();
        String getName();
        java.time.LocalDateTime getDeletedAt();
    }
    /**
     * 根据工厂ID和代码查找
     */
    Optional<RawMaterialType> findByFactoryIdAndCode(String factoryId, String code);

    /** Find by the new human-readable code without changing legacy code resolution. */
    /** 按主键取, 但保留工厂隔离 —— 跨租户读原料主数据一律走这条而不是裸 findById。 */
    Optional<RawMaterialType> findByIdAndFactoryId(String id, String factoryId);

    /**
     * 批量按主键取(工厂隔离由调用方逐行校验) —— 供画布发布校验一次性拉齐副产 SKU。
     *
     * <p>副产在本系统里是**物料**不是产品(库存落 material_batches, 该表有 byproduct_unit_price),
     * 所以画布上带 isByproduct 标记的产出节点要查这里, 而不是 product_types。
     */
    List<RawMaterialType> findByIdIn(Collection<String> ids);
     /**
     * 查找工厂的所有原材料类型
      */
    List<RawMaterialType> findByFactoryId(String factoryId);

     /**
     * 查找工厂的激活原材料类型
      */
    List<RawMaterialType> findByFactoryIdAndIsActive(String factoryId, Boolean isActive);

    List<RawMaterialType> findByFactoryIdAndPrimaryCodeOrderByCodeAsc(String factoryId, String primaryCode);
     /**
     * 分页查找工厂的原材料类型
      */
    Page<RawMaterialType> findByFactoryId(String factoryId, Pageable pageable);

    /** Server-side taxonomy subtree + keyword filtering for the material dictionary. */
    @Query("SELECT r FROM RawMaterialType r WHERE r.factoryId = :factoryId " +
           "AND (CAST(:classificationId AS long) IS NULL " +
           "OR r.classificationSegmentId = :classificationId " +
           "OR r.classificationSegmentId IN (SELECT c.id FROM MaterialCodeSegment c WHERE c.parentId = :classificationId) " +
           "OR r.classificationSegmentId IN (SELECT leaf.id FROM MaterialCodeSegment leaf " +
           "    WHERE leaf.parentId IN (SELECT child.id FROM MaterialCodeSegment child WHERE child.parentId = :classificationId))) " +
           "AND (CAST(:keyword AS string) IS NULL OR LOWER(r.name) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "OR LOWER(r.code) LIKE LOWER(CONCAT(:keyword, '%')) " +
           "OR LOWER(r.category) LIKE LOWER(CONCAT('%', :keyword, '%'))) ")
    Page<RawMaterialType> filterBySegmentPrefixAndKeyword(
            @Param("factoryId") String factoryId,
            @Param("classificationId") Long classificationId,
            @Param("keyword") String keyword,
            Pageable pageable);
     /**
     * 根据类别查找原材料类型
      */
    List<RawMaterialType> findByFactoryIdAndCategory(String factoryId, String category);
     /**
     * P11: 按物料大类(category)分页查询 — 大小写不敏感.
      */
    Page<RawMaterialType> findByFactoryIdAndCategoryIgnoreCase(String factoryId, String category, Pageable pageable);

     /**
     * 根据存储类型查找
      */
    List<RawMaterialType> findByFactoryIdAndStorageType(String factoryId, String storageType);
     /**
     * 搜索原材料类型
     * 注意：code使用右模糊（可使用索引），name/category使用双向模糊（无法使用索引）
      */
    @Query("SELECT r FROM RawMaterialType r WHERE r.factoryId = :factoryId AND " +
           "(LOWER(r.name) LIKE LOWER(CONCAT('%', :keyword, '%')) ESCAPE '\\' OR " +
           "LOWER(r.code) LIKE LOWER(CONCAT(:keyword, '%')) ESCAPE '\\' OR " +
           "LOWER(r.category) LIKE LOWER(CONCAT('%', :keyword, '%')) ESCAPE '\\')")
    Page<RawMaterialType> searchMaterialTypes(@Param("factoryId") String factoryId,
                                              @Param("keyword") String keyword,
                                              Pageable pageable);

    /** R10 CRIT-2: push-down isActive filter (see CustomerRepository). */
    Page<RawMaterialType> findByFactoryIdAndIsActiveTrue(String factoryId, Pageable pageable);

    @Query("SELECT r FROM RawMaterialType r WHERE r.factoryId = :factoryId AND r.isActive = true AND " +
           "(LOWER(r.name) LIKE LOWER(CONCAT('%', :keyword, '%')) ESCAPE '\\' OR " +
           "LOWER(r.code) LIKE LOWER(CONCAT(:keyword, '%')) ESCAPE '\\' OR " +
           "LOWER(r.category) LIKE LOWER(CONCAT('%', :keyword, '%')) ESCAPE '\\')")
    Page<RawMaterialType> searchActiveMaterialTypes(@Param("factoryId") String factoryId,
                                                    @Param("keyword") String keyword,
                                                    Pageable pageable);
     /**
     * 检查代码是否存在
      */
    boolean existsByFactoryIdAndCode(String factoryId, String code);

    @Query("SELECT CASE WHEN COUNT(r) > 0 THEN true ELSE false END FROM RawMaterialType r " +
           "WHERE r.factoryId = :factoryId AND LOWER(TRIM(r.name)) = LOWER(TRIM(:name))")
    boolean existsByFactoryIdAndNormalizedName(@Param("factoryId") String factoryId,
                                               @Param("name") String name);

    @Query("SELECT CASE WHEN COUNT(r) > 0 THEN true ELSE false END FROM RawMaterialType r " +
           "WHERE r.factoryId = :factoryId AND r.id <> :excludeId " +
           "AND LOWER(TRIM(r.name)) = LOWER(TRIM(:name))")
    boolean existsByFactoryIdAndNormalizedNameExcludingId(@Param("factoryId") String factoryId,
                                                          @Param("name") String name,
                                                          @Param("excludeId") String excludeId);
     /**
     * 统计原材料类型数量
      */
    @Query("SELECT COUNT(r) FROM RawMaterialType r WHERE r.factoryId = :factoryId AND r.isActive = true")
    Long countActiveMaterialTypes(@Param("factoryId") String factoryId);

    /**
     * 按工厂ID和激活状态统计数量
     */
    long countByFactoryIdAndIsActive(String factoryId, Boolean isActive);

    /**
     * 按工厂ID统计数量
     */
    long countByFactoryId(String factoryId);
     /**
     * 获取有库存预警的原材料类型
      */
    @Query("SELECT r FROM RawMaterialType r WHERE r.factoryId = :factoryId " +
           "AND r.minStock IS NOT NULL AND r.minStock > 0")
    List<RawMaterialType> findMaterialTypesWithStockWarning(@Param("factoryId") String factoryId);

    /**
     * 按 name 模糊匹配 + 可选 category 筛选, 返回最近创建的若干条.
     * 用于"新建原料类型"页智能默认单位: 输入名称+类别后, 取相似历史原料的 unit.
     *
     * PG 兼容: :category 为 null 时, 需 CAST(:category AS string) 让 PG 推断参数类型,
     * 否则 "could not determine data type of parameter $2" (PG 无法对纯 null 参数推类型).
     */
    @Query("SELECT r FROM RawMaterialType r WHERE r.factoryId = :factoryId AND r.isActive = true " +
           "AND (CAST(:category AS string) IS NULL OR r.category = :category) " +
           "AND LOWER(r.name) LIKE LOWER(CONCAT('%', :keyword, '%')) ESCAPE '\\' " +
           "ORDER BY r.createdAt DESC")
    List<RawMaterialType> findSimilarByNameAndCategory(@Param("factoryId") String factoryId,
                                                       @Param("keyword") String keyword,
                                                       @Param("category") String category,
                                                       Pageable pageable);

    /**
     * T159-B-codegen: 按编码前缀查找该工厂的原料编码列表 (用于生成序列号).
     * 仅取 code 字段减少传输量; 前缀区分大小写(编码全大写).
     */
    @Query(value = "SELECT code FROM raw_material_types " +
           "WHERE factory_id = :factoryId AND UPPER(code) LIKE UPPER(CONCAT(:prefix, '%')) " +
           "ORDER BY code", nativeQuery = true)
    List<String> findCodesByFactoryIdAndCodePrefix(@Param("factoryId") String factoryId,
                                                   @Param("prefix") String prefix);

    /** Includes soft-deleted rows because the database uniqueness constraint includes them. */
    @Query(value = "SELECT id, name, deleted_at AS \"deletedAt\" FROM raw_material_types " +
           "WHERE factory_id = :factoryId AND UPPER(code) = UPPER(:code) " +
           "ORDER BY CASE WHEN deleted_at IS NULL THEN 0 ELSE 1 END, id LIMIT 1",
           nativeQuery = true)
    Optional<CodeConflictView> findCodeConflictIncludingDeleted(
            @Param("factoryId") String factoryId,
            @Param("code") String code);

    /** 删除分类前的守卫：还有多少在用物料直接引用这个分类节点。 */
    @Query("SELECT COUNT(r) FROM RawMaterialType r WHERE r.factoryId = :factoryId " +
           "AND r.isActive = true AND r.classificationSegmentId = :classificationId")
    long countActiveByFactoryIdAndClassificationSegmentId(
            @Param("factoryId") String factoryId,
            @Param("classificationId") Long classificationId);

    /**
     * 按简短料号前缀搜索物料.
     * 最多返回 50 条.
     */
    @Query("SELECT r FROM RawMaterialType r WHERE r.factoryId = :factoryId " +
           "AND r.code LIKE CONCAT(:codePrefix, '%') ESCAPE '\\' " +
           "ORDER BY r.code ASC")
    List<RawMaterialType> findByFactoryIdAndCodeStartingWith(@Param("factoryId") String factoryId,
                                                              @Param("codePrefix") String codePrefix,
                                                              Pageable pageable);

    /**
     * R14: 按单价范围筛选原材料 (研发试样选料辅助).
     * unitPrice IS NOT NULL 且在 [minPrice, maxPrice] (两端 nullable, null = 无限).
     * 最多返回 100 条, 按 unitPrice ASC.
     * CAST(:min AS java.math.BigDecimal) 对 null 值 PG 类型推断安全 (per database-entity-sync.md).
     */
    @Query("SELECT r FROM RawMaterialType r WHERE r.factoryId = :factoryId " +
           "AND r.unitPrice IS NOT NULL " +
           "AND (CAST(:minPrice AS java.math.BigDecimal) IS NULL OR r.unitPrice >= :minPrice) " +
           "AND (CAST(:maxPrice AS java.math.BigDecimal) IS NULL OR r.unitPrice <= :maxPrice) " +
           "ORDER BY r.unitPrice ASC")
    List<RawMaterialType> findByPriceRange(@Param("factoryId") String factoryId,
                                           @Param("minPrice") java.math.BigDecimal minPrice,
                                           @Param("maxPrice") java.math.BigDecimal maxPrice,
                                           Pageable pageable);
}
