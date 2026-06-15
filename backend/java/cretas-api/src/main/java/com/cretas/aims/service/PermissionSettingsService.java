package com.cretas.aims.service;

import com.cretas.aims.dto.permission.PermissionSettingsDtos.EffectiveUserPermissionDto;
import com.cretas.aims.dto.permission.PermissionSettingsDtos.ModuleDto;
import com.cretas.aims.dto.permission.PermissionSettingsDtos.PermissionPreviewDto;
import com.cretas.aims.dto.permission.PermissionSettingsDtos.RoleTemplateDto;
import com.cretas.aims.dto.permission.PermissionSettingsDtos.UpdateRoleTemplateRequest;
import com.cretas.aims.dto.permission.PermissionSettingsDtos.UpdateUserOverridesRequest;

import java.util.List;

public interface PermissionSettingsService {

    List<ModuleDto> listModules(String factoryId);

    List<RoleTemplateDto> listRoleTemplates(String factoryId);

    RoleTemplateDto updateRoleTemplate(String factoryId, UpdateRoleTemplateRequest request);

    EffectiveUserPermissionDto getUserEffectivePermissions(String factoryId, String userId);

    EffectiveUserPermissionDto updateUserOverrides(String factoryId, UpdateUserOverridesRequest request);

    EffectiveUserPermissionDto clearUserOverride(String factoryId, String userId, String moduleCode);

    PermissionPreviewDto previewUserPermissions(String factoryId, String userId);
}
