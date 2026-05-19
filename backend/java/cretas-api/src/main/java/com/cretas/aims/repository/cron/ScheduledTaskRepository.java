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
     * without factoryId, this can match either scope — call sites should prefer
     * the scope-aware variants below to avoid cross-scope ambiguity.
     *
     * @deprecated since 2026-05-19 post-review C1 — non-unique across scopes;
     *   prefer {@link #findByFactoryIdAndTaskCode(String, String)} or
     *   {@link #findByTaskCodeAndFactoryIdIsNull(String)} for deterministic results.
     */
    @Deprecated
    Optional<ScheduledTask> findByTaskCode(String taskCode);

    /**
     * Find a per-factory task by (factoryId, taskCode). Used for uniqueness
     * guards in create/update so that two factories may reuse the same code
     * (per-factory scope is the intended semantic), but the same factory cannot
     * register two tasks with the same code.
     *
     * <p>Backed by partial unique index {@code idx_scheduled_tasks_factory_code}.
     */
    Optional<ScheduledTask> findByFactoryIdAndTaskCode(String factoryId, String taskCode);

    /**
     * Find a global task (factoryId IS NULL) by taskCode. Used for uniqueness
     * guards on global-scope mutations.
     *
     * <p>Backed by partial unique index {@code idx_scheduled_tasks_global_code}.
     */
    Optional<ScheduledTask> findByTaskCodeAndFactoryIdIsNull(String taskCode);

    /**
     * Load all enabled tasks for a given factory (UI list filtered by factory).
     */
    List<ScheduledTask> findByFactoryIdAndEnabledTrue(String factoryId);
}
