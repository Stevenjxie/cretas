package com.cretas.aims.repository;

import com.cretas.aims.entity.ProductType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
/**
 * 产品类型数据访问接口
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Repository
public interface ProductTypeRepository extends JpaRepository<ProductType, String> {
    /**
     * 根据工厂ID和产品代码查找
     */
    Optional<ProductType> findByFactoryIdAndCode(String factoryId, String code);

    /**
     * 根据ID和工厂ID查找（工厂隔离）
     */
    Optional<ProductType> findByIdAndFactoryId(String id, String factoryId);
     /**
     * 查找工厂的所有产品类型
      */
    List<ProductType> findByFactoryId(String factoryId);
     /**
     * 查找工厂的激活产品类型
      */
    List<ProductType> findByFactoryIdAndIsActive(String factoryId, Boolean isActive);
     /**
     * 分页查找工厂的产品类型
      */
    Page<ProductType> findByFactoryId(String factoryId, Pageable pageable);

    /**
     * 精简「选项」投影 —— 仅供下拉/SKU picker。构造器投影只 SELECT 需要的标量列,
     * 不 hydrate 实体、不解析 JSON 字段, 单查询即可返回全量, 避免重 DTO 的 ~3s/422KB 开销。
     * 排序与旧列表页一致 (createdAt DESC), 保证选择器里最近创建的产品在前。
     * 字段顺序必须与 {@link com.cretas.aims.dto.producttype.ProductTypeOptionDTO} 构造器一致。
     */
    @Query("SELECT new com.cretas.aims.dto.producttype.ProductTypeOptionDTO(" +
           "p.id, p.code, p.name, p.unit, p.specification, p.productCategory, p.isActive) " +
           "FROM ProductType p WHERE p.factoryId = :factoryId ORDER BY p.createdAt DESC")
    List<com.cretas.aims.dto.producttype.ProductTypeOptionDTO> findOptionsByFactoryId(
            @Param("factoryId") String factoryId);
     /**
     * 根据类别查找产品类型
      */
    List<ProductType> findByFactoryIdAndCategory(String factoryId, String category);
     /**
     * 搜索产品类型
     * 注意：code使用右模糊（可使用索引），name/category使用双向模糊（无法使用索引）
      */
    @Query("SELECT p FROM ProductType p WHERE p.factoryId = :factoryId AND " +
           "(LOWER(p.name) LIKE LOWER(CONCAT('%', :keyword, '%')) ESCAPE '\\' OR " +
           "LOWER(p.code) LIKE LOWER(CONCAT(:keyword, '%')) ESCAPE '\\' OR " +
           "LOWER(p.category) LIKE LOWER(CONCAT('%', :keyword, '%')) ESCAPE '\\')")
    Page<ProductType> searchProductTypes(@Param("factoryId") String factoryId,
                                         @Param("keyword") String keyword,
                                         Pageable pageable);

    /** R10 CRIT-2: push-down isActive filter (see CustomerRepository). */
    Page<ProductType> findByFactoryIdAndIsActiveTrue(String factoryId, Pageable pageable);

    @Query("SELECT p FROM ProductType p WHERE p.factoryId = :factoryId AND p.isActive = true AND " +
           "(LOWER(p.name) LIKE LOWER(CONCAT('%', :keyword, '%')) ESCAPE '\\' OR " +
           "LOWER(p.code) LIKE LOWER(CONCAT(:keyword, '%')) ESCAPE '\\' OR " +
           "LOWER(p.category) LIKE LOWER(CONCAT('%', :keyword, '%')) ESCAPE '\\')")
    Page<ProductType> searchActiveProductTypes(@Param("factoryId") String factoryId,
                                                @Param("keyword") String keyword,
                                                Pageable pageable);

    /**
     * V3 P0-2 修复 (Apr 7) — 按产品大类隔离查询.
     * 客户原话 (会议 1503-1510s): "选成品但能看到原料" — 此前 Service 完全忽略
     * productCategory 参数, 4 个 tab 共享同一份数据.
     */
    Page<ProductType> findByFactoryIdAndProductCategory(String factoryId, String productCategory, Pageable pageable);

    /** 按工厂 + 大类 + 关键词搜索 (兼顾过滤 + 搜索两种场景) */
    @Query("SELECT p FROM ProductType p WHERE p.factoryId = :factoryId " +
           "AND p.productCategory = :productCategory AND " +
           "(LOWER(p.name) LIKE LOWER(CONCAT('%', :keyword, '%')) ESCAPE '\\' OR " +
           " LOWER(p.code) LIKE LOWER(CONCAT(:keyword, '%')) ESCAPE '\\')")
    Page<ProductType> searchByFactoryIdAndProductCategory(@Param("factoryId") String factoryId,
                                                          @Param("productCategory") String productCategory,
                                                          @Param("keyword") String keyword,
                                                          Pageable pageable);
     /**
     * 检查产品代码是否存在
      */
    boolean existsByFactoryIdAndCode(String factoryId, String code);

    /**
     * 检查产品类型是否存在（工厂隔离）
     */
    boolean existsByIdAndFactoryId(String id, String factoryId);
     /**
     * 统计产品类型数量
      */
    @Query("SELECT COUNT(p) FROM ProductType p WHERE p.factoryId = :factoryId AND p.isActive = true")
    Long countActiveProductTypes(@Param("factoryId") String factoryId);
     /**
     * 统计工厂的产品类型总数
      */
    long countByFactoryId(String factoryId);

    /**
     * 查找工厂的所有激活产品类型
     * 用于蓝图导出功能
     *
     * @param factoryId 工厂ID
     * @return 产品类型列表
     */
    List<ProductType> findByFactoryIdAndIsActiveTrue(String factoryId);

    /** 查询产品模板列表 (templateId IS NULL, 即基础产品) */
    List<ProductType> findByFactoryIdAndTemplateIdIsNullAndIsActiveTrue(String factoryId);

    /** 查询某模板下的所有SKU */
    List<ProductType> findByFactoryIdAndTemplateIdAndIsActiveTrue(String factoryId, String templateId);

    /**
     * 批量查询多个产品类型 - 解决 N+1 查询问题
     * @param ids 产品类型ID集合
     * @return 产品类型列表
     */
    List<ProductType> findByIdIn(java.util.Collection<String> ids);

    /**
     * 根据工厂ID和产品名称查找
     * 用于Excel导入时按名称解析产品类型
     */
    Optional<ProductType> findByFactoryIdAndName(String factoryId, String name);
}
