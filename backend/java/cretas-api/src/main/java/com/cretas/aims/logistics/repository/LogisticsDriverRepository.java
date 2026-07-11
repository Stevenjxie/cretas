package com.cretas.aims.logistics.repository;

import com.cretas.aims.logistics.entity.LogisticsDriver;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LogisticsDriverRepository extends JpaRepository<LogisticsDriver, String> {

    List<LogisticsDriver> findByFactoryIdAndActiveTrueAndDeletedAtIsNull(String factoryId);

    /** Phase 2: GET /drivers 资源管理列表 — 含 inactive (管理员需要看到并可重新启用)。 */
    List<LogisticsDriver> findByFactoryIdOrderByNameAsc(String factoryId);

    /** Phase 2: 租户隔离查询 (update / 绑定校验)。 */
    Optional<LogisticsDriver> findByIdAndFactoryId(String id, String factoryId);
}
