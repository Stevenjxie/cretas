package com.cretas.aims.repository.config;

import com.cretas.aims.entity.config.ModuleSchema;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ModuleSchemaRepository extends JpaRepository<ModuleSchema, Long> {
    Optional<ModuleSchema> findByModuleCode(String moduleCode);
    List<ModuleSchema> findByIsActiveTrue();
    List<ModuleSchema> findByModuleCategoryAndIsActiveTrue(String category);
    boolean existsByModuleCode(String moduleCode);
}
