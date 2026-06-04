package com.cretas.aims.utils;

import com.cretas.aims.exception.BusinessException;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.Set;

/**
 * 报工鉴权守卫: 工序任务归属校验 + 代报 (targetWorkerId) 主管门控.
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
     * 归属守卫: 确认登录用户 (workerId) 可以报工该任务.
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
}
