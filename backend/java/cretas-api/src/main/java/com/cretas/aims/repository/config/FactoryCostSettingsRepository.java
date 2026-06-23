package com.cretas.aims.repository.config;

import com.cretas.aims.entity.config.FactoryCostSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository for {@link FactoryCostSettings}.
 * Used by {@code ClerkProcessEntryServiceImpl} to resolve the per-factory
 * labor hourly rate (¥/工时); falls back to {@code LABOR_RATE_DEFAULT} when absent.
 */
@Repository
public interface FactoryCostSettingsRepository extends JpaRepository<FactoryCostSettings, Long> {
    Optional<FactoryCostSettings> findByFactoryId(String factoryId);
}
