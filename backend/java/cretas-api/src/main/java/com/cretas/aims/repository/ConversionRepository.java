package com.cretas.aims.repository;

import com.cretas.aims.entity.MaterialProductConversion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 转换率数据访问接口
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Repository
public interface ConversionRepository extends JpaRepository<MaterialProductConversion, String> {

    /**
     * 根据工厂ID查找所有转换率配置
     */
    Page<MaterialProductConversion> findByFactoryId(String factoryId, Pageable pageable);

    /**
     * 根据工厂ID和激活状态查找
     */
    Page<MaterialProductConversion> findByFactoryIdAndIsActive(String factoryId, Boolean isActive, Pageable pageable);

    /**
     * 根据原材料类型查找转换率
     */
    List<MaterialProductConversion> findByFactoryIdAndMaterialTypeId(String factoryId, String materialTypeId);

    /**
     * 根据产品类型查找转换率
     */
    @EntityGraph(attributePaths = {"materialType", "productType"})
    List<MaterialProductConversion> findByFactoryIdAndProductTypeId(String factoryId, String productTypeId);

    /**
     * 查找特定原材料和产品的转换率
     */
    Optional<MaterialProductConversion> findByFactoryIdAndMaterialTypeIdAndProductTypeId(
            String factoryId, String materialTypeId, String productTypeId);

    /**
     * 检查转换率配置是否存在
     */
    boolean existsByFactoryIdAndMaterialTypeIdAndProductTypeId(
            String factoryId, String materialTypeId, String productTypeId);

    /**
     * 根据原材料类型ID查找所有可转换的产品
     */
    @Query("SELECT DISTINCT c.productTypeId FROM MaterialProductConversion c " +
           "WHERE c.factoryId = :factoryId AND c.materialTypeId = :materialTypeId " +
           "AND c.isActive = true")
    List<String> findProductTypesByMaterialType(@Param("factoryId") String factoryId,
                                                  @Param("materialTypeId") String materialTypeId);

    /**
     * 根据产品类型ID查找所有需要的原材料
     */
    @Query("SELECT DISTINCT c.materialTypeId FROM MaterialProductConversion c " +
           "WHERE c.factoryId = :factoryId AND c.productTypeId = :productTypeId " +
           "AND c.isActive = true")
    List<Integer> findMaterialTypesByProductType(@Param("factoryId") String factoryId,
                                                  @Param("productTypeId") Integer productTypeId);

    /**
     * 批量更新激活状态
     */
    @Modifying
    @Transactional
    @Query("UPDATE MaterialProductConversion c SET c.isActive = :isActive " +
           "WHERE c.factoryId = :factoryId AND c.id IN :ids")
    void updateActiveStatus(@Param("factoryId") String factoryId,
                           @Param("ids") List<String> ids,
                           @Param("isActive") Boolean isActive);

    /**
     * 删除工厂的所有转换率配置
     */
    void deleteByFactoryId(String factoryId);

    /**
     * 统计工厂的转换率配置数量
     */
    long countByFactoryIdAndIsActive(String factoryId, Boolean isActive);
}
