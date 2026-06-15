package com.cretas.aims.dto.permission;

import java.util.List;

public final class PermissionSettingsDtos {

    private PermissionSettingsDtos() {
    }

    public record ModuleDto(
            String moduleCode,
            String displayName,
            String parentCode,
            String parentName,
            String routePath,
            int sortOrder,
            boolean writeSupported) {
    }

    public record ModulePermissionDto(
            String moduleCode,
            String permissionLevel,
            String source) {
    }

    public record RoleTemplateDto(
            String roleCode,
            String roleName,
            List<ModulePermissionDto> modules) {
    }

    public record UpdateRoleTemplateRequest(
            String roleCode,
            List<ModulePermissionDto> modules) {
    }

    public record UpdateUserOverridesRequest(
            String userId,
            List<ModulePermissionDto> modules) {
    }

    public record EffectiveUserPermissionDto(
            String userId,
            String roleCode,
            List<ModulePermissionDto> modules) {
    }

    public record PermissionPreviewDto(
            String userId,
            List<ModulePermissionDto> visibleModules,
            List<ModulePermissionDto> deniedModules,
            List<ModulePermissionDto> editableModules) {
    }
}
