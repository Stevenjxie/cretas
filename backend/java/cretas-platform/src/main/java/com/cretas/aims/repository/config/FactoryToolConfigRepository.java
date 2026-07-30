package com.cretas.aims.repository.config;

import com.cretas.aims.entity.config.FactoryToolConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FactoryToolConfigRepository extends JpaRepository<FactoryToolConfig, Long> {
    List<FactoryToolConfig> findByFactoryId(String factoryId);
    Optional<FactoryToolConfig> findByFactoryIdAndToolName(String factoryId, String toolName);
    List<FactoryToolConfig> findByFactoryIdAndEnabledFalse(String factoryId);
}
