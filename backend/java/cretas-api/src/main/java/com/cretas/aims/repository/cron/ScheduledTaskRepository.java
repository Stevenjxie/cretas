package com.cretas.aims.repository.cron;

import com.cretas.aims.entity.cron.ScheduledTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link ScheduledTask}.
 *
 * <p>Phase 5 skeleton — sister chat adds custom queries as needed.
 *
 * @since Phase 5 (2026-05-18)
 */
@Repository
public interface ScheduledTaskRepository extends JpaRepository<ScheduledTask, UUID> {

    /**
     * Load all enabled tasks (for DynamicScheduler bootstrap + reload).
     */
    List<ScheduledTask> findByEnabledTrue();

    /**
     * Find by task_code. Returns at most one row because of partial unique index
     * (per scope: global if factoryId NULL, per-factory otherwise). When called
     * without factoryId, this can match either scope — sister chat ensures call
     * sites match within intended scope.
     */
    Optional<ScheduledTask> findByTaskCode(String taskCode);

    /**
     * Load all enabled tasks for a given factory (UI list filtered by factory).
     */
    List<ScheduledTask> findByFactoryIdAndEnabledTrue(String factoryId);
}
