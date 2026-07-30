package com.cretas.aims.repository.config;

import com.cretas.aims.entity.config.FactoryModuleConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FactoryModuleConfigRepository extends JpaRepository<FactoryModuleConfig, Long> {
    Optional<FactoryModuleConfig> findByFactoryIdAndModuleCodeAndConfigVersion(
            String factoryId, String moduleCode, Integer configVersion);

    List<FactoryModuleConfig> findByFactoryIdAndConfigVersion(String factoryId, Integer configVersion);

    List<FactoryModuleConfig> findByFactoryIdAndConfigVersionAndEnabledTrue(
            String factoryId, Integer configVersion);

    boolean existsByFactoryIdAndModuleCodeAndConfigVersion(
            String factoryId, String moduleCode, Integer configVersion);
}
