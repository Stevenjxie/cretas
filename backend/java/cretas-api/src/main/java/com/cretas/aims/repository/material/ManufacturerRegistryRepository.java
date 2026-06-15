package com.cretas.aims.repository.material;

import com.cretas.aims.entity.material.ManufacturerRegistry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ManufacturerRegistryRepository extends JpaRepository<ManufacturerRegistry, String> {

    List<ManufacturerRegistry> findByFactoryIdAndDeletedAtIsNullOrderByCodeAsc(String factoryId);

    List<ManufacturerRegistry> findByFactoryIdAndIsActiveTrueAndDeletedAtIsNullOrderByCodeAsc(String factoryId);

    Optional<ManufacturerRegistry> findByFactoryIdAndCodeAndDeletedAtIsNull(String factoryId, String code);
}
