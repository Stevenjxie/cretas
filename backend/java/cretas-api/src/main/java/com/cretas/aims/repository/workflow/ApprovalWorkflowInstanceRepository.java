package com.cretas.aims.repository.workflow;

import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance.InstanceStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Approval workflow instance repository — Phase 1 B.3.
 *
 * <p>Note on soft-delete: {@link ApprovalWorkflowInstance} 在类上声明
 * {@code @Where(clause = "deleted_at IS NULL")}, 所有查询自动过滤.
 *
 * @since 2026-05-18
 */
@Repository
public interface ApprovalWorkflowInstanceRepository
        extends JpaRepository<ApprovalWorkflowInstance, String> {

    /**
     * 业务实体查实例 — {@code PurchaseServiceImpl.findInstanceByPO} 用 (B.6).
     *
     * <p>同一 (factory, module, business entity) 理论上至多 1 个 active 实例 (业务约束).
     * 历史归档实例也命中 — caller 应进一步 filter by status 如需排除终态.
     */
    Optional<ApprovalWorkflowInstance> findByFactoryIdAndModuleCodeAndBusinessEntityIdAndStatus(
            String factoryId,
            String moduleCode,
            String businessEntityId,
            InstanceStatus status);

    /** Latest instance for a business document, including terminal instances. */
    Optional<ApprovalWorkflowInstance>
            findFirstByFactoryIdAndModuleCodeAndBusinessEntityIdOrderByInitiatedAtDesc(
                    String factoryId,
                    String moduleCode,
                    String businessEntityId);

    /**
     * 工厂维度按 status 查 — 用于 factory 级别统计或定向恢复.
     */
    List<ApprovalWorkflowInstance> findByFactoryIdAndStatus(
            String factoryId, InstanceStatus status);

    /**
     * 启动恢复 — 全工厂扫所有 RUNNING 实例.
     *
     * <p>调用方: {@code WorkflowEngineServiceImpl#rebuildRedisFromPg}
     * (注解 {@code @EventListener(ApplicationReadyEvent.class)}).
     *
     * <p>命中 partial index {@code idx_aw_instances_recovery}
     * ({@code WHERE status='RUNNING' AND deleted_at IS NULL}).
     */
    List<ApprovalWorkflowInstance> findByStatus(InstanceStatus status);

    /**
     * Phase D 报表统计 — 按 workflow + status 维度.
     */
    List<ApprovalWorkflowInstance> findByFactoryIdAndWorkflowIdAndStatus(
            String factoryId, String workflowId, InstanceStatus status);

    /**
     * issue #20 — "我待审" widget query.
     *
     * <p>按 (factoryId, status, moduleCode) 过滤, 按 initiatedAt 倒序返. caller
     * 拿到原始 list 后还要根据 active node 的 approverRoles 二次过滤
     * (in-memory, 因 approverRoles 在 workflow.nodesJson 里, 不能 SQL filter).
     *
     * <p>命中索引 {@code idx_aw_instances_recovery (factory_id, status)}.
     *
     * @param factoryId 工厂 id
     * @param status 通常传 RUNNING
     * @param moduleCode 可空; 空时不过滤 module
     * @since 2026-05-18
     */
    List<ApprovalWorkflowInstance> findByFactoryIdAndStatusAndModuleCodeOrderByInitiatedAtDesc(
            String factoryId, InstanceStatus status, String moduleCode);

    /**
     * issue #20 — "我待审" widget 当 moduleCode=null 时使用 (查全部 module).
     */
    List<ApprovalWorkflowInstance> findByFactoryIdAndStatusOrderByInitiatedAtDesc(
            String factoryId, InstanceStatus status);

    /**
     * Sprint 5 Track A — "我创建的工作流" personal view query.
     *
     * <p>列出当前 user 作为 initiator 的所有 workflow 实例 (跨 status 跨 module).
     * UI 默认按 initiatedAt DESC 排序, 用户可看自己起的 RUNNING / APPROVED / REJECTED /
     * CANCELLED 全状态历史.
     *
     * <p>命中 (factory_id, initiated_by) 复合索引 — V20260608_05 添加.
     *
     * @param factoryId 工厂 id
     * @param initiatedBy user.id
     * @param pageable 分页参数
     * @return Page of instances sorted by initiatedAt DESC
     * @since 2026-05-19 (Sprint 5 Track A)
     */
    Page<ApprovalWorkflowInstance> findByFactoryIdAndInitiatedByOrderByInitiatedAtDesc(
            String factoryId, Long initiatedBy, Pageable pageable);

    /**
     * Sprint 5 Track A — "我参与的工作流" personal view query.
     *
     * <p>列出当前 user 发起或作为 actor 处理过的所有 workflow 实例.
     * Distinct 因为同一 user 可能多次操作同一实例 (APPROVE → DELEGATE 等).
     *
     * <p>不限定 status — 用户查看自己发起或处理过的所有实例 (含已完成).
     * 默认按 initiatedAt DESC 排序 — 实例发起时间, 不是参与时间 (UI 显示一致).
     *
     * @param factoryId 工厂 id
     * @param actorId user.id (instance.initiated_by 或 history.actor_id)
     * @param pageable 分页参数
     * @return Page of instances sorted by initiatedAt DESC
     * @since 2026-05-19 (Sprint 5 Track A)
     */
    @Query(value = "SELECT DISTINCT i FROM ApprovalWorkflowInstance i " +
                   "WHERE i.factoryId = :factoryId AND (i.initiatedBy = :actorId OR i.id IN (" +
                   "  SELECT h.instanceId FROM com.cretas.aims.entity.workflow.ApprovalHistory h " +
                   "  WHERE h.factoryId = :factoryId AND h.actorId = :actorId" +
                   ")) " +
                   "ORDER BY i.initiatedAt DESC",
           countQuery = "SELECT COUNT(DISTINCT i) FROM ApprovalWorkflowInstance i " +
                        "WHERE i.factoryId = :factoryId AND (i.initiatedBy = :actorId OR i.id IN (" +
                        "  SELECT h.instanceId FROM com.cretas.aims.entity.workflow.ApprovalHistory h " +
                        "  WHERE h.factoryId = :factoryId AND h.actorId = :actorId" +
                        "))")
    Page<ApprovalWorkflowInstance> findParticipatedBy(
            @Param("factoryId") String factoryId,
            @Param("actorId") Long actorId,
            Pageable pageable);

    /**
     * Sprint 6 W1-B — admin view "工作流处理" query.
     *
     * <p>列出当前 factory 下所有指定 status (通常 RUNNING) workflow 实例, 跨 module
     * 跨 initiator. 给 super-admin / platform_admin 看全局视角, 排查卡住的流程.
     *
     * <p><b>调用方必须</b> 通过 controller 层 {@code @PreAuthorize} 守门. 此方法
     * 本身不做 RBAC 过滤.
     *
     * <p>命中索引 {@code idx_aw_instances_recovery (factory_id, status)} (partial index).
     *
     * @param factoryId 工厂 id
     * @param status 通常传 RUNNING
     * @param pageable 分页参数
     * @return Page of instances sorted by initiatedAt DESC
     * @since 2026-05-19 (Sprint 6 W1-B)
     */
    Page<ApprovalWorkflowInstance> findByFactoryIdAndStatusOrderByInitiatedAtDesc(
            String factoryId, InstanceStatus status, Pageable pageable);
}
