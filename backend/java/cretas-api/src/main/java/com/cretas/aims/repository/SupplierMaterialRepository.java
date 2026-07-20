package com.cretas.aims.repository;

import com.cretas.aims.entity.SupplierMaterial;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.List;
import java.util.Optional;

public interface SupplierMaterialRepository extends JpaRepository<SupplierMaterial, String> {
    Optional<SupplierMaterial> findByIdAndFactoryId(String id, String factoryId);
    Optional<SupplierMaterial> findByFactoryIdAndSupplierIdAndMaterialTypeId(
            String factoryId, String supplierId, String materialTypeId);
    List<SupplierMaterial> findByFactoryIdAndSupplierIdOrderByActiveDescPreferredDescCreatedAtDesc(
            String factoryId, String supplierId);
    List<SupplierMaterial> findByFactoryIdAndMaterialTypeIdOrderByActiveDescPreferredDescCreatedAtDesc(
            String factoryId, String materialTypeId);
    boolean existsByFactoryIdAndSupplierIdAndMaterialTypeIdAndActiveTrue(
            String factoryId, String supplierId, String materialTypeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<SupplierMaterial> findByFactoryIdAndMaterialTypeIdAndActiveTrue(
            String factoryId, String materialTypeId);
}
