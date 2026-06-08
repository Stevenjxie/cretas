package com.cretas.aims.repository;

import com.cretas.aims.entity.RawMaterialType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
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
    /**
     * 根据工厂ID和代码查找
     */
    Optional<RawMaterialType> findByFactoryIdAndCode(String factoryId, String code);
     /**
     * 查找工厂的所有原材料类型
      */
    List<RawMaterialType> findByFactoryId(String factoryId);
     /**
     * 查找工厂的激活原材料类型
      */
    List<RawMaterialType> findByFactoryIdAndIsActive(String factoryId, Boolean isActive);
     /**
     * 分页查找工厂的原材料类型
      */
    Page<RawMaterialType> findByFactoryId(String factoryId, Pageable pageable);
     /**
     * 根据类别查找原材料类型
      */
    List<RawMaterialType> findByFactoryIdAndCategory(String factoryId, String category);
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
    @Query("SELECT r.code FROM RawMaterialType r WHERE r.factoryId = :factoryId " +
           "AND r.code LIKE CONCAT(:prefix, '%') ESCAPE '\\'")
    List<String> findCodesByFactoryIdAndCodePrefix(@Param("factoryId") String factoryId,
                                                   @Param("prefix") String prefix);
}
