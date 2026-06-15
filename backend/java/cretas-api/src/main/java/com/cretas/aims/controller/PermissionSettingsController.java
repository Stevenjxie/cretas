package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.permission.PermissionSettingsDtos.UpdateRoleTemplateRequest;
import com.cretas.aims.dto.permission.PermissionSettingsDtos.UpdateUserOverridesRequest;
import com.cretas.aims.service.PermissionSettingsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mobile/{factoryId}/permissions")
@RequireModule("permission_settings")
@RequireRole({"factory_super_admin", "platform_admin", "permission_admin"})
public class PermissionSettingsController {

    private final PermissionSettingsService permissionSettingsService;

    public PermissionSettingsController(PermissionSettingsService permissionSettingsService) {
        this.permissionSettingsService = permissionSettingsService;
    }

    @GetMapping("/modules")
    public ResponseEntity<?> listModules(@PathVariable String factoryId) {
        return ResponseEntity.ok(permissionSettingsService.listModules(factoryId));
    }

    @GetMapping("/role-templates")
    public ResponseEntity<?> listRoleTemplates(@PathVariable String factoryId) {
        return ResponseEntity.ok(permissionSettingsService.listRoleTemplates(factoryId));
    }

    @PutMapping("/role-templates")
    public ResponseEntity<?> updateRoleTemplate(
            @PathVariable String factoryId,
            @RequestBody UpdateRoleTemplateRequest request) {
        return ResponseEntity.ok(permissionSettingsService.updateRoleTemplate(factoryId, request));
    }

    @GetMapping("/users/{userId}/effective")
    public ResponseEntity<?> getUserEffectivePermissions(
            @PathVariable String factoryId,
            @PathVariable String userId) {
        return ResponseEntity.ok(permissionSettingsService.getUserEffectivePermissions(factoryId, userId));
    }

    @PutMapping("/users/{userId}/overrides")
    public ResponseEntity<?> updateUserOverrides(
            @PathVariable String factoryId,
            @PathVariable String userId,
            @RequestBody UpdateUserOverridesRequest request) {
        UpdateUserOverridesRequest normalized = new UpdateUserOverridesRequest(userId, request.modules());
        return ResponseEntity.ok(permissionSettingsService.updateUserOverrides(factoryId, normalized));
    }

    @DeleteMapping("/users/{userId}/overrides/{moduleCode}")
    public ResponseEntity<?> clearUserOverride(
            @PathVariable String factoryId,
            @PathVariable String userId,
            @PathVariable String moduleCode) {
        return ResponseEntity.ok(permissionSettingsService.clearUserOverride(factoryId, userId, moduleCode));
    }

    @GetMapping("/users/{userId}/preview")
    public ResponseEntity<?> previewUserPermissions(
            @PathVariable String factoryId,
            @PathVariable String userId) {
        return ResponseEntity.ok(permissionSettingsService.previewUserPermissions(factoryId, userId));
    }
}
