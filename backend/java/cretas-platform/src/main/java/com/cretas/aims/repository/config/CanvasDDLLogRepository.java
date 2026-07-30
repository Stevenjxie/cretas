package com.cretas.aims.repository.config;

import com.cretas.aims.entity.config.CanvasDDLLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CanvasDDLLogRepository extends JpaRepository<CanvasDDLLog, String> {

    List<CanvasDDLLog> findByFactoryIdOrderByCreatedAtDesc(String factoryId);

    /** Round 5 Fix PERF-3: paginated DDL log to bound memory usage. */
    Page<CanvasDDLLog> findByFactoryIdOrderByCreatedAtDesc(String factoryId, Pageable pageable);

    List<CanvasDDLLog> findByFactoryIdAndConfigVersion(String factoryId, Integer configVersion);
}
