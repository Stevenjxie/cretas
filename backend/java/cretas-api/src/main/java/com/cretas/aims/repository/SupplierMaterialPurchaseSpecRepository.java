package com.cretas.aims.repository;

import com.cretas.aims.entity.SupplierMaterialPurchaseSpec;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import java.util.List;
import java.util.Optional;

public interface SupplierMaterialPurchaseSpecRepository extends JpaRepository<SupplierMaterialPurchaseSpec, String> {
    Optional<SupplierMaterialPurchaseSpec> findByIdAndFactoryId(String id, String factoryId);
    List<SupplierMaterialPurchaseSpec> findByFactoryIdAndSupplierMaterialIdOrderByActiveDescDefaultSpecDescCreatedAtDesc(
            String factoryId, String supplierMaterialId);
    List<SupplierMaterialPurchaseSpec> findByFactoryIdAndSupplierMaterialIdAndActiveTrue(
            String factoryId, String supplierMaterialId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<SupplierMaterialPurchaseSpec> findByFactoryIdAndSupplierMaterialIdAndActiveTrueOrderByCreatedAtAsc(
            String factoryId, String supplierMaterialId);
}
