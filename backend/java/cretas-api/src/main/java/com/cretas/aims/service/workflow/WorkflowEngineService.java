package com.cretas.aims.service.workflow;

import com.cretas.aims.entity.workflow.ApprovalHistory;
import com.cretas.aims.entity.workflow.ApprovalHistory.HistoryAction;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.ApprovalWorkflow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Workflow engine — Phase 1 B.3 持久化层入口.
 *
 * <p>替换 Sprint 3 Track-I {@link ApprovalWorkflowExecutor} 的内存实现:
 * <ul>
 *   <li>实例持久化 — Redis hot cache + PG shadow store</li>
 *   <li>状态机推进 — start → condition → ... → approval → end</li>
 *   <li>SpEL 条件评估 — 走 {@link SandboxedSpelEvaluator} 沙箱</li>
 *   <li>启动恢复 — 重启时拉 RUNNING 行重建 Redis</li>
 * </ul>
 *
 * <p><b>当前实现状态 (B.3 skeleton)</b>:
 * <ul>
 *   <li>{@code startWorkflow / evaluateCondition / rebuildRedisFromPg / getCurrentInstance / getHistory} 已实现</li>
 *   <li>{@code transitionNode / cancel} → B.4 完成 DAG advance 逻辑</li>
 * </ul>
 *
 * @since 2026-05-18 (Phase 1 B.3)
 */
public interface WorkflowEngineService {

    /**
     * 检查是否有 active workflow 配置 — caller 用此避免触发 startWorkflow 的 throw 路径,
     * 防止 Spring "rollback-only" 事务陷阱 (Phase 1 prod hotfix 2026-05-18).
     *
     * <p>使用场景: PurchaseServiceImpl 等业务模块 approve 前预检. 无 workflow 时
     * caller 自行走 legacy fallback, 不进 startWorkflow.
     *
     * @return true 若 (factoryId, moduleCode) 有 published+enabled workflow.
     *         moduleCode 未映射 → false (不抛, 保持调用侧简洁).
     */
    boolean hasActiveWorkflow(String factoryId, String moduleCode);

    /**
     * 启动 workflow 实例.
     *
     * <p>流程:
     * <ol>
     *   <li>moduleCode → DecisionType 映射</li>
     *   <li>{@code ApprovalWorkflowService.getActiveByDecisionType} 取 active workflow</li>
     *   <li>创建 instance, 写入 PG + Redis (key {@code aw:instance:{id}})</li>
     *   <li>从 startNode 走到第一个非自动节点 (approval 或 end)</li>
     *   <li>写 {@code START} history record</li>
     * </ol>
     *
     * @param factoryId 工厂 id
     * @param moduleCode 业务模块 (PURCHASE_ORDER / SALES_ORDER / ...)
     * @param businessEntityId 业务实体 id (PO id / SO id)
     * @param contextJson 业务上下文 — SpEL 评估时绑定为 {@code #context.xxx}
     * @param initiatorUserId 发起人, 系统触发时可 NULL
     * @return 创建的实例 (status=RUNNING, 已 walk 到第一个 human/end 节点)
     * @throws IllegalArgumentException moduleCode 未映射, 或无 active workflow
     */
    ApprovalWorkflowInstance startWorkflow(String factoryId,
                                           String moduleCode,
                                           String businessEntityId,
                                           Map<String, Object> contextJson,
                                           Long initiatorUserId);

    /** Start from one exact caller-validated definition, rechecked by the engine. */
    ApprovalWorkflowInstance startWorkflowWithDefinition(
            String factoryId,
            String moduleCode,
            String businessEntityId,
            Map<String, Object> contextJson,
            Long initiatorUserId,
            ApprovalWorkflow expectedWorkflow);

    /**
     * 推进一个节点 (用户审批 / 自动转换 / 超时).
     *
     * <p><b>B.4 实现</b> — DAG advance 逻辑: 找当前 active 节点 outgoing edges,
     * 评估 condition 走第一个 true 的, 重复直到 approval/end. parallel/join
     * 处理多分支.
     *
     * @param instanceId workflow 实例 id
     * @param actorId 操作人 (AUTO_TRANSITION / TIMEOUT 时 NULL)
     * @param actorRole 操作人 role (同上)
     * @param action APPROVE / REJECT / SKIP / DELEGATE / TIMEOUT / AUTO_TRANSITION
     * @param notes 自由文本备注
     * @return 推进后的实例 (可能仍 RUNNING, 或已到终态)
     */
    ApprovalWorkflowInstance transitionNode(String instanceId,
                                            Long actorId,
                                            String actorRole,
                                            HistoryAction action,
                                            String notes);

    /**
     * 业务实体查实例 — PurchaseService.findInstanceByPO 用 (B.6).
     *
     * <p>同一 (factoryId, moduleCode, businessEntityId) 理论上至多 1 active.
     */
    Optional<ApprovalWorkflowInstance> getCurrentInstance(String factoryId,
                                                          String moduleCode,
                                                          String businessEntityId);

    /** Latest instance for read models and idempotency checks, including terminal state. */
    Optional<ApprovalWorkflowInstance> getLatestInstance(String factoryId,
                                                         String moduleCode,
                                                         String businessEntityId);

    /** Factory-scoped instance lookup for the unified OA action endpoint. */
    Optional<ApprovalWorkflowInstance> getInstance(String factoryId, String instanceId);

    /** Whether the user may operate the active node, including factory scope. */
    boolean canTransition(ApprovalWorkflowInstance instance, User user);

    /**
     * 时间线 UI — 实例完整历史按时间升序.
     */
    List<ApprovalHistory> getHistory(String factoryId, String instanceId);

    /**
     * SpEL 条件评估 (沙箱化, 经 {@link SandboxedSpelEvaluator}).
     *
     * <p>表达式形如 {@code #context.amount > 30000}, {@code #context.department == 'finance'}.
     *
     * @param spelExpression SpEL 字符串
     * @param context 业务上下文 map, 绑定为 {@code #context}
     * @return true 当且仅当结果是 {@code Boolean.TRUE}
     */
    boolean evaluateCondition(String spelExpression, Map<String, Object> context);

    /**
     * 取消 workflow 实例 — 业务方主动 cancel.
     *
     * <p><b>B.4 实现</b> — 设 status=CANCELLED, completedAt=now, 写 CANCEL history.
     */
    ApprovalWorkflowInstance cancel(String instanceId, Long cancellerUserId, String reason);

    /**
     * issue #20 — 查 user 角色待审的 RUNNING instances.
     *
     * <p>过滤逻辑:
     * <ol>
     *   <li>SQL filter: factoryId + status=RUNNING + (moduleCode=? 若非空)</li>
     *   <li>对每个 instance: 取 workflow.nodesJson, 找 currentNodeIds 中
     *       type=approval 节点, 检查 config.approverRoles 是否含 userRole</li>
     *   <li>{@code factory_super_admin} 总是匹配全部 (兜底)</li>
     *   <li>按 initiatedAt DESC 排序 (来自 SQL)</li>
     *   <li>内存分页 (pageable.offset / pageSize)</li>
     * </ol>
     *
     * <p>性能: in-memory filter 因 workflow.nodesJson 在 JSONB 不便 SQL filter.
     * Phase 1 数据量预期低 (< 100 实例/工厂/天), 可接受. 后续 Phase 量大时
     * 可缓存 workflow nodes 或拍平 approver_roles 单表.
     *
     * @param factoryId 工厂 id
     * @param userRole user.roleCode (e.g. "finance_manager")
     * @param moduleCode 可空 — 过滤业务模块
     * @param pageable 分页参数 (page from 0)
     * @return Page of instances sorted by initiatedAt DESC
     * @since 2026-05-18 (issue #20)
     */
    Page<ApprovalWorkflowInstance> findPendingForRole(
            String factoryId, String userRole, String moduleCode, Pageable pageable);

    /**
     * 启动 hook — 从 PG 重建 Redis state.
     *
     * <p>实现注解 {@code @EventListener(ApplicationReadyEvent.class)}, 应用启动后
     * 扫所有 factory 的 RUNNING 实例 HSET 进 Redis (key {@code aw:instance:{id}}).
     * Redis fail-open: 重建失败仅 log warning, PG 仍是 source of truth.
     */
    void rebuildRedisFromPg();

    /**
     * Sprint 5 Track A — "我创建的工作流" personal view.
     *
     * <p>查 user 作为 initiator 的 workflow instances. 跨 status 跨 module 返全部.
     * UI 调用方应根据 status 用不同 tag 显示 (RUNNING 黄 / APPROVED 绿 / REJECTED 红 / CANCELLED 灰).
     *
     * @param factoryId 工厂 id
     * @param userId 当前登录 user.id
     * @param pageable 分页参数 (page from 0)
     * @return Page of instances sorted by initiatedAt DESC
     * @since 2026-05-19
     */
    Page<ApprovalWorkflowInstance> findCreatedBy(
            String factoryId, Long userId, Pageable pageable);

    /**
     * Sprint 5 Track A — "我参与的工作流" personal view.
     *
     * <p>查 user 作为 actor 操作过的 workflow instances (通过 ApprovalHistory).
     * Distinct 因同一 user 可能多次操作同一实例.
     *
     * @param factoryId 工厂 id
     * @param userId 当前登录 user.id
     * @param pageable 分页参数 (page from 0)
     * @return Page of instances sorted by initiatedAt DESC
     * @since 2026-05-19
     */
    Page<ApprovalWorkflowInstance> findParticipatedBy(
            String factoryId, Long userId, Pageable pageable);

    /**
     * Sprint 6 W1-B — "工作流处理" admin view (super-admin only).
     *
     * <p>列出当前 factory 下所有 RUNNING workflow 实例 (跨 module 跨 initiator
     * 跨 role). 给 super-admin / platform_admin 看全局视角, 用于:
     * <ul>
     *   <li>排查卡住的流程 (谁在审, 多久没动)</li>
     *   <li>跨部门协调 (找负责人)</li>
     *   <li>Bulk admin 操作 — Sprint 6 W4+ 加 force-cancel / delegate</li>
     * </ul>
     *
     * <p>注意: 此方法 <b>不做 role-based 过滤</b> — 调用方 controller 必须用
     * {@code @PreAuthorize("hasAnyRole('FACTORY_SUPER_ADMIN', 'PLATFORM_ADMIN')")}
     * 守门, 否则普通 user 可看全部 instances 违反 RBAC.
     *
     * @param factoryId 工厂 id
     * @param pageable 分页参数 (page from 0)
     * @return Page of RUNNING instances sorted by initiatedAt DESC
     * @since 2026-05-19 (Sprint 6 W1-B)
     */
    Page<ApprovalWorkflowInstance> listAllRunning(
            String factoryId, Pageable pageable);
}
