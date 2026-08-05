package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.config.FactoryModuleConfig;
import com.cretas.aims.repository.config.FactoryModuleConfigRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Layer 2 (Factory override) permission matrix management API.
 *
 * See: docs/superpowers/specs/2026-04-18-permission-matrix-ai-driven-design.md §5.1.4
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/canvas/role-module-override")
@RequiredArgsConstructor
@Tag(name = "Factory Role Module Override", description = "L2 factory-level permission override")
public class FactoryRoleModuleOverrideController {

    private static final Set<String> ALLOWED_MODULES = Set.of(
        "dashboard","production","warehouse","quality","procurement","sales",
        "hr","equipment","finance","system","analytics","scheduling",
        "work_report","inventory","report","rd","restaurant",
        // 2026-08-05 餐饮四部门驾驶舱细分权限。语义见 permission.ts:552:
        //   最终 = min(restaurant 上限, 该部门声明值 ?? 上限)
        // 大小写敏感, 必须与前端 ModulePermissions 的键逐字一致。
        "restaurantOps","restaurantMarketing","restaurantHr","restaurantFinance");
    private static final Set<String> ALLOWED_LEVELS = Set.of("rw","r","w","-");

    static void assertModuleAllowed(String module) {
        if (!ALLOWED_MODULES.contains(module)) {
            throw new IllegalArgumentException("无效模块: " + module);
        }
    }

    private final FactoryModuleConfigRepository factoryConfigRepo;
    private final PermissionService permissionService;

    @GetMapping
    @Operation(summary = "获取本工厂 role × module override (L2)")
    public ApiResponse<Map<String, Map<String, String>>> get(@PathVariable String factoryId) {
        Map<String, Map<String, String>> merged = new HashMap<>();
        List<FactoryModuleConfig> configs = factoryConfigRepo.findByFactoryIdAndConfigVersion(factoryId, 1);
        for (FactoryModuleConfig cfg : configs) {
            Map<String, Map<String, String>> override = cfg.getRoleModuleOverride();
            if (override == null) continue;
            for (Map.Entry<String, Map<String, String>> e : override.entrySet()) {
                merged.computeIfAbsent(e.getKey(), k -> new HashMap<>()).putAll(e.getValue());
            }
        }
        return ApiResponse.success(merged);
    }

    @PutMapping("/{role}/{module}")
    @Operation(summary = "更新本工厂 override. level=null 清除回归全局默认")
    @RequirePermission({"system:read_write"})
    public ApiResponse<Void> update(
            @PathVariable String factoryId,
            @PathVariable String role,
            @PathVariable String module,
            @RequestParam(required = false) String level) {

        assertModuleAllowed(module);
        if (level != null && !ALLOWED_LEVELS.contains(level)) {
            throw new IllegalArgumentException("无效权限级别: " + level);
        }

        // Find or create sentinel config row for overrides
        List<FactoryModuleConfig> configs = factoryConfigRepo.findByFactoryIdAndConfigVersion(factoryId, 1);
        FactoryModuleConfig target = configs.stream()
            .filter(c -> "__permissions__".equals(c.getModuleCode()))
            .findFirst()
            .orElse(null);

        if (target == null) {
            target = FactoryModuleConfig.builder()
                .factoryId(factoryId)
                .moduleCode("__permissions__")
                .configVersion(1)
                .enabled(true)
                .fieldConfig(Map.of())
                .workflowConfig(Map.of())
                .validationConfig(Map.of())
                .permissionConfig(Map.of())
                .layoutConfig(Map.of())
                .customLabels(Map.of())
                .computedFields(Map.of())
                .roleModuleOverride(Map.of())
                .renderingMode("LEGACY")
                .build();
        }

        Map<String, Map<String, String>> override = target.getRoleModuleOverride();
        if (override == null || override.isEmpty()) {
            override = new HashMap<>();
        } else {
            // Defensive copy for mutation safety
            Map<String, Map<String, String>> copy = new HashMap<>();
            for (Map.Entry<String, Map<String, String>> e : override.entrySet()) {
                copy.put(e.getKey(), new HashMap<>(e.getValue()));
            }
            override = copy;
        }

        if (level == null) {
            // Clear override for this (role, module)
            Map<String, String> roleOverrides = override.get(role);
            if (roleOverrides != null) {
                roleOverrides.remove(module);
                if (roleOverrides.isEmpty()) override.remove(role);
            }
        } else {
            override.computeIfAbsent(role, k -> new HashMap<>()).put(module, level);
        }

        target.setRoleModuleOverride(override);
        factoryConfigRepo.save(target);

        if (permissionService instanceof PermissionServiceImpl) {
            ((PermissionServiceImpl) permissionService).invalidateCache();
        }

        return ApiResponse.success(null);
    }
}
