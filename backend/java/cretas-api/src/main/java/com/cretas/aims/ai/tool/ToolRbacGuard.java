package com.cretas.aims.ai.tool;

import com.cretas.aims.entity.User;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.PermissionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * W5 红线 (AI-RBAC): AI Tool 执行链的 RBAC 守卫。
 *
 * <p>背景 (Fable 系统性审计): HTTP 面 RBAC 由 controller {@code @RequirePermission} 收口
 * (经 {@code PermissionInterceptor} → {@code PermissionService.hasAnyPermission})。但
 * <b>AI tool 直调 service 绕过了 controller 拦截器</b> —— 敏感写工具 (删批次/改入库量/财审退货)
 * 在 AI 对话路径上没有任何鉴权, W0 WriteGuard 只是"确认门"不做鉴权。结果: 仓管经 AI 删批次,
 * 非财务经 AI 财审退货, 均能绕过 HTTP 面的 403。
 *
 * <p>本守卫把 controller 用的同一套权限判定 ({@link PermissionService#hasAnyPermission}) 搬进
 * tool 执行链: 从 context 的 {@code userId} 加载 {@link User}, 用真实角色+工厂级 (L2/L1/矩阵)
 * 权限矩阵判定。与 {@link com.cretas.aims.config.PermissionInterceptor#getCurrentUser} 同源
 * (都按 userId 查 User), 因此 AI 路径与 HTTP 路径鉴权口径完全一致。
 *
 * <p><b>fail-closed</b>: 加载失败 / userId 缺失 / user 不存在 → 拒绝。敏感写操作宁可误拒不可误放。
 */
@Slf4j
@Component
public class ToolRbacGuard {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PermissionService permissionService;

    /**
     * 判断 AI 调用者是否拥有指定权限之一 (镜像 controller {@code @RequirePermission} 的
     * {@code requireAll=false} 任一语义)。
     *
     * @param context tool 执行上下文 (须含 userId)
     * @param permissionCodes 需要的权限码 (任一即可), 如 {@code "warehouse:read_write"}
     * @return true=放行; false=拒绝 (含解析失败的 fail-closed)
     */
    public boolean hasAnyPermission(Map<String, Object> context, String... permissionCodes) {
        try {
            User user = loadUser(context);
            if (user == null) {
                return false;
            }
            return permissionService.hasAnyPermission(user, permissionCodes);
        } catch (Exception e) {
            log.warn("AI tool RBAC 解析失败, fail-closed 拒绝: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 拒绝时构造一致的提示文案 (4 位一体: 谁的角色 / 缺什么权限 / 怎么办)。
     */
    public String denyMessage(Map<String, Object> context, String action, String... permissionCodes) {
        String role = context != null ? String.valueOf(context.getOrDefault("userRole", "未知")) : "未知";
        return String.format(
                "您当前的角色 (%s) 没有权限执行「%s」操作。此操作涉及敏感数据/库存/资金, 需要授权角色。"
                        + "建议: 请在系统对应页面由有权限的角色 (如仓储主管/财务/管理员) 操作, 或联系工厂管理员申请权限。",
                role, action);
    }

    private User loadUser(Map<String, Object> context) {
        if (context == null) {
            return null;
        }
        Object userIdObj = context.get("userId");
        if (userIdObj == null) {
            return null;
        }
        Long userId;
        if (userIdObj instanceof Long) {
            userId = (Long) userIdObj;
        } else if (userIdObj instanceof Integer) {
            userId = ((Integer) userIdObj).longValue();
        } else if (userIdObj instanceof String) {
            try {
                userId = Long.parseLong((String) userIdObj);
            } catch (NumberFormatException e) {
                return null;
            }
        } else {
            return null;
        }
        return userRepository.findById(userId).orElse(null);
    }
}
