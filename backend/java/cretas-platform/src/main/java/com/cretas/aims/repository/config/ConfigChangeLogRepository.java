package com.cretas.aims.repository.config;

import com.cretas.aims.entity.config.ConfigChangeLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ConfigChangeLogRepository extends JpaRepository<ConfigChangeLog, Long> {
    Page<ConfigChangeLog> findByFactoryIdOrderByCreatedAtDesc(String factoryId, Pageable pageable);
    Page<ConfigChangeLog> findByFactoryIdAndModuleCodeOrderByCreatedAtDesc(
            String factoryId, String moduleCode, Pageable pageable);
}
