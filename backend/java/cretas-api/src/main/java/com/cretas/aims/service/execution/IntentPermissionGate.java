package com.cretas.aims.service.execution;

import com.cretas.aims.ai.tool.ToolRbacGuard;
import com.cretas.aims.entity.config.AIIntentConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 意图级权限门 (AI 读写分块 P1, 2026-07-23 spec §4.2)。
 *
 * <p>统一意图层权限判定到 HTTP 层同一套 {@code module:action} 权限矩阵:
 * {@code AIIntentConfig.requiredPermission} 非空 → 经 {@link ToolRbacGuard}
 * (同 {@code PermissionService.hasAnyPermission}, 与 controller
 * {@code @RequirePermission} / {@code ToolRbacEnforcer} 完全同源) 判定;
 * 空 → 返回 {@link PermissionCheck#legacy()} 标记, 由调用方保留既有
 * {@code aiIntentService.hasPermission(intentCode, userRole)} requiredRoles
 * 旧逻辑 (兼容期, 避免双查)。
 *
 * <p><b>fail-closed</b>: 权限码已声明时, userId 缺失 / User 不存在 / 判定异常
 * 一律拒绝并回带缺失权限码 (前端渲染「需要 X 权限」提示)。
 */
@Slf4j
@Component
public class IntentPermissionGate {

    @Autowired
    private ToolRbacGuard rbacGuard;

    /**
     * @return ALLOWED=放行; DENIED=按权限码拒绝 (含 requiredPermission);
     *         LEGACY=未配置权限码, 调用方走 requiredRoles 旧逻辑
     */
    public PermissionCheck check(AIIntentConfig intent, Long userId, String userRole) {
        if (intent == null) {
            // 无意图无从设门, 由调用方处理
            return PermissionCheck.allowed();
        }
        String code = intent.getRequiredPermission();
        if (code == null || code.isBlank()) {
            return PermissionCheck.legacy();
        }
        try {
            if (userId == null) {
                // fail-closed: 无法定位调用者身份即拒绝
                return PermissionCheck.denied(code);
            }
            // P1-M (2026-07-24): 逗号分隔 = 任一即可 (any-of), 与 ToolRbacEnforcer 的
            // Set 语义完全对齐 — 多码工具 (如 customer_delete: sales|finance) 的意图层
            // 回填不收紧权限。单码时行为不变。
            String[] codes = java.util.Arrays.stream(code.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
            boolean ok = codes.length > 0
                    && rbacGuard.hasAnyPermission(Map.of("userId", userId), codes);
            if (!ok) {
                log.warn("意图权限门拒绝: intentCode={}, requiredPermission={}, userId={}, role={}",
                        intent.getIntentCode(), code, userId, userRole);
            }
            return ok ? PermissionCheck.allowed() : PermissionCheck.denied(code);
        } catch (Exception e) {
            // fail-closed: 判定异常按拒绝处理
            log.warn("意图权限门判定异常, fail-closed 拒绝: intentCode={}, error={}",
                    intent.getIntentCode(), e.getMessage());
            return PermissionCheck.denied(code);
        }
    }

    /**
     * 取调用者的权限码全集, 供识别层候选过滤 (spec §8.2, OPERATE tab 剔除无权限写意图)。
     * 与 {@link #check} 同源 ({@link ToolRbacGuard} → PermissionService 三层矩阵)。
     *
     * @return 权限码集合; null = 无法解析 (调用方应跳过权限过滤, 交给真正的鉴权门)
     */
    public java.util.Set<String> resolveUserPermissions(Long userId) {
        if (userId == null || rbacGuard == null) {
            return null;
        }
        return rbacGuard.resolveUserPermissions(Map.of("userId", userId));
    }

    /**
     * 判定结果。{@code requiredPermission} 仅在按权限码拒绝时非 null。
     */
    public record PermissionCheck(Result result, String requiredPermission) {

        public enum Result { ALLOWED, DENIED, LEGACY }

        public static PermissionCheck allowed() {
            return new PermissionCheck(Result.ALLOWED, null);
        }

        public static PermissionCheck denied(String requiredPermission) {
            return new PermissionCheck(Result.DENIED, requiredPermission);
        }

        public static PermissionCheck legacy() {
            return new PermissionCheck(Result.LEGACY, null);
        }

        public boolean isAllowed() {
            return result == Result.ALLOWED;
        }

        public boolean isDenied() {
            return result == Result.DENIED;
        }

        public boolean isLegacy() {
            return result == Result.LEGACY;
        }
    }
}
