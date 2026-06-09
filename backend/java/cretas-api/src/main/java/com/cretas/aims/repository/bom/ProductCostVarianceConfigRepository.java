package com.cretas.aims.repository.bom;

import com.cretas.aims.entity.bom.ProductCostVarianceConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * SP3: ProductCostVarianceConfig Repository.
 *
 * <p>阈值解析优先级:
 * <ol>
 *   <li>product-specific: factory_id + product_type_id NOT NULL</li>
 *   <li>factory-global:   factory_id + product_type_id IS NULL</li>
 *   <li>system default:   10% (Java layer fallback, no DB row)</li>
 * </ol>
 */
@Repository
public interface ProductCostVarianceConfigRepository
        extends JpaRepository<ProductCostVarianceConfig, Long> {

    /** 查询产品专属配置 */
    Optional<ProductCostVarianceConfig>
    findByFactoryIdAndProductTypeIdAndIsActiveTrueAndDeletedAtIsNull(
            String factoryId, String productTypeId);

    /** 查询工厂全局默认配置 (productTypeId IS NULL) */
    Optional<ProductCostVarianceConfig>
    findByFactoryIdAndProductTypeIdIsNullAndIsActiveTrueAndDeletedAtIsNull(
            String factoryId);

    /**
     * 删除时软删：通过 BaseEntity @SQLDelete
     * 直接用 findById + delete 即可触发 BaseEntity soft-delete.
     */

    /**
     * 列表查询（工厂级，含 global + product-specific 全部）.
     */
    java.util.List<ProductCostVarianceConfig>
    findByFactoryIdAndDeletedAtIsNullOrderByProductTypeIdAsc(String factoryId);
}
