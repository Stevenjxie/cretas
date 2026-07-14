package com.cretas.aims.repository.product;

import com.cretas.aims.entity.product.ProductPackagingSpec;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductPackagingSpecRepository extends JpaRepository<ProductPackagingSpec, String> {

    List<ProductPackagingSpec> findByFactoryIdAndProductTypeIdOrderBySortOrderAscCreatedAtAsc(
            String factoryId, String productTypeId);

    List<ProductPackagingSpec> findByFactoryIdAndProductTypeIdAndActiveTrueOrderBySortOrderAscCreatedAtAsc(
            String factoryId, String productTypeId);

    Optional<ProductPackagingSpec> findByIdAndFactoryIdAndProductTypeIdAndActiveTrue(
            String id, String factoryId, String productTypeId);
}
