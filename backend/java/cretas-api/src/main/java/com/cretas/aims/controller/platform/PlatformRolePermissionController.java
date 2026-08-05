package com.cretas.aims.controller.platform;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.permission.PlatformRolePermission;
import com.cretas.aims.repository.permission.PlatformRolePermissionRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import com.cretas.aims.utils.TokenUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * Layer 1 (Platform global default) permission matrix management API.
 *
 * See: docs/superpowers/specs/2026-04-18-permission-matrix-ai-driven-design.md §5.1.4
 */
/**
 * L1 API:
 * - GET /api/admin/role-permissions — any authenticated factory admin can read (to build matrix view)
 * - PUT /api/platform/role-permissions/{role}/{module} — platform_admin only (global edits)
 *
 * Split paths because JwtAuthInterceptor:
 *   /api/admin/** → any authenticated user
 *   /api/platform/** → only platform_admin role
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "Platform Role Permissions", description = "L1 global permission matrix management")
public class PlatformRolePermissionController {

    private static final Set<String> ALLOWED_MODULES = Set.of(
        "dashboard","production","warehouse","quality","procurement","sales",
        "hr","equipment","finance","system","analytics","scheduling",
        "work_report","inventory","report","rd","restaurant",
        // 2026-08-05 餐饮四部门驾驶舱细分权限。语义见 permission.ts:552:
        //   最终 = min(restaurant 上限, 该部门声明值 ?? 上限)
        // 大小写敏感, 必须与前端 ModulePermissions 的键逐字一致。
        "restaurantOps","restaurantMarketing","restaurantHr","restaurantFinance");
    private static final Set<String> ALLOWED_LEVELS = Set.of("rw","r","w","-");

    public static void assertModuleAllowed(String module) {
        if (!ALLOWED_MODULES.contains(module)) {
            throw new IllegalArgumentException("无效模块: " + module + ". 允许: " + ALLOWED_MODULES);
        }
    }

    private final PlatformRolePermissionRepository repo;
    private final PermissionService permissionService;
    private final MobileService mobileService;

    @GetMapping("/api/admin/role-permissions")
    @Operation(summary = "获取全平台 role × module 权限矩阵 (L1) — 所有认证用户可读")
    public ApiResponse<List<PlatformRolePermission>> getAll() {
        return ApiResponse.success(repo.findByDeletedAtIsNull());
    }

    @PutMapping("/api/platform/role-permissions/{role}/{module}")
    @Operation(summary = "更新 L1 权限级别 (platform_admin only via path interceptor)")
    @RequirePermission({"system:read_write"})
    public ApiResponse<PlatformRolePermission> update(
            @PathVariable String role,
            @PathVariable String module,
            @RequestParam String level,
            @RequestHeader("Authorization") String authorization) {

        validateModule(module);
        validateLevel(level);

        // Circular lockout guards
        if ("platform_admin".equals(role) && "system".equals(module) && !"rw".equals(level)) {
            throw new IllegalArgumentException("禁止降低 platform_admin 对 system 的权限 (会锁死管理 UI)");
        }
        if ("factory_super_admin".equals(role) && "system".equals(module) && !"rw".equals(level)) {
            throw new IllegalArgumentException("禁止降低 factory_super_admin 对 system 的权限");
        }

        Long userId = null;
        try {
            userId = mobileService.getUserFromToken(TokenUtils.extractToken(authorization)).getId();
        } catch (Exception e) {
            log.warn("无法从 token 获取 userId: {}", e.getMessage());
        }

        PlatformRolePermission saved = repo.findByRoleCodeAndModuleCodeAndDeletedAtIsNull(role, module)
            .map(p -> {
                p.setPermissionLevel(level);
                p.setUpdatedBy(null);
                return p;
            })
            .orElseGet(() -> {
                PlatformRolePermission p = new PlatformRolePermission();
                p.setRoleCode(role);
                p.setModuleCode(module);
                p.setPermissionLevel(level);
                return p;
            });
        saved.setUpdatedBy(userId);
        saved = repo.save(saved);

        // Invalidate permission cache
        if (permissionService instanceof PermissionServiceImpl) {
            ((PermissionServiceImpl) permissionService).invalidateCache();
        }

        return ApiResponse.success(saved);
    }

    private void validateModule(String module) {
        assertModuleAllowed(module);
    }

    private void validateLevel(String level) {
        if (!ALLOWED_LEVELS.contains(level)) {
            throw new IllegalArgumentException("无效权限级别: " + level + ". 允许: rw/r/w/-");
        }
    }
}
