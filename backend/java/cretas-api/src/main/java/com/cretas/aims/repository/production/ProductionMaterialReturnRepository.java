package com.cretas.aims.repository.production;

import com.cretas.aims.entity.production.ProductionMaterialReturn;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProductionMaterialReturnRepository extends JpaRepository<ProductionMaterialReturn, String> {
    Page<ProductionMaterialReturn> findByFactoryIdAndDeletedAtIsNull(String factoryId, Pageable pageable);

    Page<ProductionMaterialReturn> findByFactoryIdAndRequisitionIdAndDeletedAtIsNull(
            String factoryId, String requisitionId, Pageable pageable);
}
