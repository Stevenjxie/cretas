package com.cretas.aims.repository.material;

import com.cretas.aims.entity.material.MaterialPackagingSpec;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MaterialPackagingSpecRepository extends JpaRepository<MaterialPackagingSpec, String> {

    List<MaterialPackagingSpec> findByFactoryIdAndMaterialTypeIdOrderBySortOrderAscCreatedAtAsc(
            String factoryId, String materialTypeId);

    List<MaterialPackagingSpec> findByFactoryIdAndMaterialTypeIdAndActiveTrueOrderBySortOrderAscCreatedAtAsc(
            String factoryId, String materialTypeId);

    Optional<MaterialPackagingSpec> findByIdAndFactoryIdAndMaterialTypeIdAndActiveTrue(
            String id, String factoryId, String materialTypeId);
}
