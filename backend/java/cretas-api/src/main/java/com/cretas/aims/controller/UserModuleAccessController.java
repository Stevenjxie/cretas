package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.UserModuleAccess;
import com.cretas.aims.permission.ProductionModuleRegistry;
import com.cretas.aims.service.UserModuleAccessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/mobile/{factoryId}/canvas/user-module-access")
@RequiredArgsConstructor
@Tag(name = "User Module Access", description = "L4 per-account module access override")
public class UserModuleAccessController {

    private final UserModuleAccessService userModuleAccessService;

    @GetMapping("/registry")
    @Operation(summary = "List registered production module codes for user access UI")
    @RequirePermission({"system:read", "system:read_write"})
    public ApiResponse<List<ProductionModuleRegistry.ModuleDefinition>> registry() {
        return ApiResponse.success(ProductionModuleRegistry.modules());
    }

    @GetMapping("/{userId}")
    @Operation(summary = "Get effective user module access: role default plus user override")
    @RequirePermission({"system:read", "system:read_write"})
    public ApiResponse<List<UserModuleAccessService.ModuleAccessView>> get(
            @PathVariable String factoryId,
            @PathVariable String userId) {
        return ApiResponse.success(userModuleAccessService.listEffectiveAccess(factoryId, userId));
    }

    @PutMapping("/{userId}/{module}")
    @Operation(summary = "Set user-level module access override")
    @RequirePermission({"system:read_write"})
    public ApiResponse<UserModuleAccess> update(
            @PathVariable String factoryId,
            @PathVariable String userId,
            @PathVariable String module,
            @RequestParam UserModuleAccess.AccessType action,
            @RequestParam(required = false) String remark,
            HttpServletRequest request) {
        UserModuleAccess saved = userModuleAccessService.setOverride(
                factoryId,
                userId,
                module,
                action,
                currentUserId(request),
                remark);
        return ApiResponse.success(saved);
    }

    @DeleteMapping("/{userId}/{module}")
    @Operation(summary = "Clear user-level module access override and restore role default")
    @RequirePermission({"system:read_write"})
    public ApiResponse<Void> delete(
            @PathVariable String factoryId,
            @PathVariable String userId,
            @PathVariable String module) {
        userModuleAccessService.clearOverride(factoryId, userId, module);
        return ApiResponse.success(null);
    }

    private String currentUserId(HttpServletRequest request) {
        Object userId = request.getAttribute("userId");
        return userId == null ? null : String.valueOf(userId);
    }
}
