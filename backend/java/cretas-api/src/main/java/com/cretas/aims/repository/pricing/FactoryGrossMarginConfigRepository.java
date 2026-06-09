package com.cretas.aims.repository.pricing;

import com.cretas.aims.entity.pricing.FactoryGrossMarginConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * SP5: 工厂毛利红线配置 Repository.
 */
@Repository
public interface FactoryGrossMarginConfigRepository extends JpaRepository<FactoryGrossMarginConfig, String> {

    /**
     * 查找指定产品类型的配置 (精确匹配).
     */
    Optional<FactoryGrossMarginConfig> findByFactoryIdAndProductTypeIdAndIsActiveTrue(
            String factoryId, String productTypeId);

    /**
     * 查找工厂全局默认配置 (productTypeId = null).
     */
    @Query("SELECT c FROM FactoryGrossMarginConfig c " +
           "WHERE c.factoryId = :factoryId " +
           "AND c.productTypeId IS NULL " +
           "AND c.isActive = true")
    Optional<FactoryGrossMarginConfig> findFactoryDefault(@Param("factoryId") String factoryId);

    /**
     * 查询工厂全部激活配置.
     */
    List<FactoryGrossMarginConfig> findByFactoryIdAndIsActiveTrueOrderByProductTypeIdAsc(String factoryId);
}
