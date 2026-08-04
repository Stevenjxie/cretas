package com.cretas.aims.repository.workprocess;

import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.entity.workprocess.WorkProcessTask.Status;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 工序任务 Repository (Track D2 — M-WP-1/2).
 */
@Repository
public interface WorkProcessTaskRepository extends JpaRepository<WorkProcessTask, Long> {

    long countByFactoryIdAndWorkProcessId(String factoryId, String workProcessId);

    /**
     * 列出某批次的全部工序任务 (按 processOrder 升序).
     */
    List<WorkProcessTask> findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(
            String factoryId, Long productionBatchId);

    List<WorkProcessTask> findByFactoryIdAndWorkflowInstanceIdOrderByProcessOrderAsc(
            String factoryId, Long workflowInstanceId);

    /**
     * 列出某批次某状态的工序任务 (按 processOrder 升序).
     */
    List<WorkProcessTask> findByFactoryIdAndProductionBatchIdAndStatusOrderByProcessOrderAsc(
            String factoryId, Long productionBatchId, Status status);

    /**
     * 单条 (factoryId + id) 多租户隔离查询.
     */
    Optional<WorkProcessTask> findByFactoryIdAndId(String factoryId, Long id);

    /**
     * 检查某批次是否已 spawn 过工序任务 (防重 spawn).
     */
    boolean existsByFactoryIdAndProductionBatchId(String factoryId, Long productionBatchId);

    /**
     * 检查某批次下某个 SKU 是否已 spawn 过工序任务.
     */
    boolean existsByFactoryIdAndProductionBatchIdAndProductTypeId(
            String factoryId, Long productionBatchId, String productTypeId);

    /** 批量按 id 取任务 (audit YIELD-4: enrich processName 避免 N+1). */
    List<WorkProcessTask> findByFactoryIdAndIdIn(String factoryId, Collection<Long> ids);

    /**
     * R8 双栈合并: 按 factoryId + productionBatchId + workProcessId 查唯一工序任务.
     *
     * <p>用于按批次与工序定位唯一的 canonical task：
     * 当 ProcessTask.productionRunId = "BATCH-{batchId}" 时, 通过
     * (factoryId, productionBatchId, workProcessId) 关联键找对应的 WorkProcessTask.
     * 一个批次内同一工序只有一个实例, 结果唯一.
     */
    Optional<WorkProcessTask> findByFactoryIdAndProductionBatchIdAndWorkProcessId(
            String factoryId, Long productionBatchId, String workProcessId);

    /**
     * 分页列表 — 按 factoryId + status 过滤.
     *
     * 用 CAST(:status AS string) 应对 PostgreSQL 严格类型推断
     * (.claude/rules/database-entity-sync.md: parameter-side IS NULL 必须显式 CAST).
     *
     * <p><b>{@code assignedTo} 的语义 = 「我的 + 未指派」, 不是严格相等</b> —— 与姊妹查询
     * {@code WorkProcessTaskServiceImpl#listByBatch} 的 M1 兜底同口径
     * (那里注释原文「防止未配默认责任人的老批次把任何人锁死」)。
     *
     * <p>⛔ 别改回 {@code t.assignedTo = :assignedTo} 严格相等: 2026-08-04 prod 实测指派配置
     * <b>从未被填写过</b> (work_process_tasks 18 条 assigned_to 全 null / product_work_process_assignees
     * 0 行 / production_plans 10 条无一填 supervisor), 严格相等会让 RN 操作员报工屏的第一跳恒返回空列表,
     * 手机端报工无从开始。兜底在 listByBatch 里早就有, 但那道门在「已经进了某个批次」之后才开,
     * 操作员卡在更早的入口上永远走不到。
     *
     * <p>放开未指派是安全的: {@code ReportAuthGuard#assertCanReport} 对空允许集合本就 fail-open,
     * {@code WorkProcessTaskServiceImpl#start} 也会在 assignedTo 为 null 时自动认领 ——
     * 「未指派可被任何人捡起」是既有设计, 本查询只是与之一致。
     * <b>指派给他人的仍然过滤掉</b> (那是越权), 由
     * {@code WorkProcessTaskUnassignedVisibilityRepositoryQueryValidationTest} 两个方向各钉一条。
     */
    @Query("SELECT t FROM WorkProcessTask t WHERE t.factoryId = :factoryId "
            + "AND (CAST(:status AS string) IS NULL OR t.status = :status) "
            + "AND (CAST(:productionBatchId AS long) IS NULL OR t.productionBatchId = :productionBatchId) "
            + "AND (CAST(:assignedTo AS long) IS NULL OR t.assignedTo = :assignedTo OR t.assignedTo IS NULL) "
            + "ORDER BY t.createdAt DESC")
    Page<WorkProcessTask> findByFilters(
            @Param("factoryId") String factoryId,
            @Param("status") Status status,
            @Param("productionBatchId") Long productionBatchId,
            @Param("assignedTo") Long assignedTo,
            Pageable pageable);

}
