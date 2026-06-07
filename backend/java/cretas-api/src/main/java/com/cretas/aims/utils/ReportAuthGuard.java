package com.cretas.aims.utils;

import com.cretas.aims.exception.BusinessException;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 报工鉴权守卫: 工序任务归属校验 + 代报 (targetWorkerId) 主管门控.
 *
 * <p>T121 更新: 支持多人负责. 允许报工的 worker 集合 =
 * (join 表 product_work_process_assignees 中的所有 active worker_id)
 * ∪ ({responsible_worker_id} 作为兜底, 当 join 表为空时).
 * 集合中任意成员均可报工.
 *
 * <p>纯静态工具类, 无 Spring 依赖, 可在 plain JUnit5 测试中直接调用 (无需 MockMvc/WebMvc).
 * 角色从 HTTP 请求属性 "role" 读取 (由 JwtAuthInterceptor 注入); 无请求上下文时 currentRole()
 * 返回 null (在 Service 单元测试中 — 无 request context — 视为非主管, 方便测试边界场景).
 *
 * <p>主管角色: factory_super_admin, workshop_supervisor, department_admin (大小写不敏感).
 */
public final class ReportAuthGuard {

    private static final Set<String> SUPERVISOR_ROLES =
            Set.of("factory_super_admin", "workshop_supervisor", "department_admin");

    private ReportAuthGuard() {}

    /**
     * 当前角色是否为主管 (factory_super_admin / workshop_supervisor / department_admin).
     * null 或空 → false. 大小写不敏感.
     */
    public static boolean isSupervisor(String role) {
        if (role == null) return false;
        return SUPERVISOR_ROLES.contains(role.trim().toLowerCase());
    }

    /**
     * 从 HTTP 请求上下文读取当前角色 (JwtAuthInterceptor 注入的 "role" 属性).
     * 无请求上下文 (如纯单元测试) → null.
     */
    public static String currentRole() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs == null) return null;
        Object role = attrs.getAttribute("role", RequestAttributes.SCOPE_REQUEST);
        return role != null ? role.toString() : null;
    }

    /**
     * 单值归属守卫 (向后兼容): 确认登录用户 (workerId) 可以报工该任务.
     *
     * <p>T121: 当 join 表有多个 assignee 时, 请改用
     * {@link #assertCanReport(Long, Collection, Long, boolean)}.
     * 此重载等价于 assignees = singleton(assignedTo).
     *
     * <ul>
     *   <li>assignedTo == null → 任务未指派给任何人 → 任何操作员均可报工 → 通过.
     *   <li>assignedTo == workerId → 自己的任务 → 通过.
     *   <li>assignedTo != workerId → 指派给他人 → 仅主管 (isSupervisor=true) 可绕过; 否则 403.
     * </ul>
     *
     * @param assignedTo   任务的归属小组长 ID (WorkProcessTask.assignedTo), null 表示未指派
     * @param workerId     当前登录用户 ID (JWT 中的 userId)
     * @param isSupervisor 当前用户是否为主管角色
     * @throws BusinessException(403) 非主管用户试图报告他人任务
     */
    public static void assertCanReport(Long assignedTo, Long workerId, boolean isSupervisor) {
        if (assignedTo != null && !assignedTo.equals(workerId) && !isSupervisor) {
            throw new BusinessException(403, "该工序已指派给他人, 您无权报工")
                    .withHint("此工序的责任小组长不是您, 请报自己负责的工序");
        }
    }

    /**
     * T121 多人负责归属守卫: 允许 <em>多人负责集合</em> 中的任意成员报工.
     *
     * <p>允许集合 = join 表 assignee worker_ids (如非空) ∪ ({responsibleWorkerId} 兜底, 如 join 表为空).
     * 调用方通过 {@link #buildPermittedSet} 构建此集合.
     *
     * <ul>
     *   <li>permittedWorkerIds 为空 → 任务未指派给任何人 → 任何操作员均可报工 → 通过.
     *   <li>workerId ∈ permittedWorkerIds → 通过.
     *   <li>workerId ∉ permittedWorkerIds 且 isSupervisor → 通过 (主管豁免).
     *   <li>workerId ∉ permittedWorkerIds 且 非主管 → 403.
     * </ul>
     *
     * @param responsibleWorkerId  工序主负责人 ID (兜底; join 表为空时用)
     * @param joinTableAssignees   join 表 active assignee worker_ids (可为空)
     * @param workerId             当前登录用户 ID
     * @param isSupervisor         当前用户是否为主管角色
     * @throws BusinessException(403) 非主管用户不在允许集合内
     */
    public static void assertCanReport(Long responsibleWorkerId,
                                       Collection<Long> joinTableAssignees,
                                       Long workerId,
                                       boolean isSupervisor) {
        Set<Long> permitted = buildPermittedSet(responsibleWorkerId, joinTableAssignees);
        if (!permitted.isEmpty() && !permitted.contains(workerId) && !isSupervisor) {
            throw new BusinessException(403, "您不是该工序的负责人, 无权报工")
                    .withHint("该工序的负责人列表中没有您, 请联系主管或确认您负责的工序");
        }
    }

    /**
     * T121: 构建允许报工的 worker id 集合.
     *
     * <p>算法:
     * <ol>
     *   <li>如果 joinTableAssignees 非空 → 使用 join 表结果 (忽略 responsibleWorkerId,
     *       因为 join 表已包含 backfill 的 primary).
     *   <li>否则 (join 表为空) → 用 responsibleWorkerId 作兜底 (向后兼容未迁移的旧行).
     * </ol>
     *
     * <p>结果为空 → 未指派, 任何操作员均可报工.
     */
    public static Set<Long> buildPermittedSet(Long responsibleWorkerId,
                                              Collection<Long> joinTableAssignees) {
        if (joinTableAssignees != null && !joinTableAssignees.isEmpty()) {
            return Collections.unmodifiableSet(new HashSet<>(joinTableAssignees));
        }
        // Fallback: no join table rows → fall back to responsible_worker_id
        if (responsibleWorkerId != null) {
            return Set.of(responsibleWorkerId);
        }
        return Set.of();  // unassigned — anyone may report
    }
}
