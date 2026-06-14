package com.cretas.aims.repository.smartbi.postgres;

import com.cretas.aims.entity.smartbi.postgres.SmartBiQueryTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Bound to the smartbi_prod_db datasource (see SmartBIPostgresDataSourceConfig
 * @EnableJpaRepositories basePackages = "com.cretas.aims.repository.smartbi.postgres").
 */
public interface SmartBiQueryTemplateRepository extends JpaRepository<SmartBiQueryTemplate, Long> {

    List<SmartBiQueryTemplate> findByFactoryIdAndDeletedAtIsNullOrderByCreatedAtDesc(String factoryId);

    Optional<SmartBiQueryTemplate> findByIdAndFactoryIdAndDeletedAtIsNull(Long id, String factoryId);
}
