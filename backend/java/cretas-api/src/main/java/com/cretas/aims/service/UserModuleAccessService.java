package com.cretas.aims.service;

import com.cretas.aims.entity.User;
import com.cretas.aims.entity.UserModuleAccess;
import com.cretas.aims.permission.PermissionLevel;

import java.util.List;

public interface UserModuleAccessService {

    record ModuleAccessView(
            String moduleCode,
            String displayName,
            String category,
            String permissionModule,
            boolean roleDefaultAllowed,
            String override,
            boolean effectiveAllowed,
            String roleDefaultLevel,
            String effectiveLevel) {

        public ModuleAccessView(
                String moduleCode,
                String displayName,
                String category,
                String permissionModule,
                boolean roleDefaultAllowed,
                String override,
                boolean effectiveAllowed) {
            this(
                    moduleCode,
                    displayName,
                    category,
                    permissionModule,
                    roleDefaultAllowed,
                    override,
                    effectiveAllowed,
                    roleDefaultAllowed ? PermissionLevel.WRITE.apiCode() : PermissionLevel.HIDDEN.apiCode(),
                    effectiveAllowed ? PermissionLevel.WRITE.apiCode() : PermissionLevel.HIDDEN.apiCode());
        }
    }

    boolean canAccessModule(User user, String moduleCode);

    PermissionLevel getEffectiveLevel(String factoryId, String userId, String roleCode, String moduleCode);

    boolean canWriteModule(String factoryId, String userId, String roleCode, String moduleCode);

    List<ModuleAccessView> listEffectiveAccess(String factoryId, String userId);

    UserModuleAccess setOverride(
            String factoryId,
            String userId,
            String moduleCode,
            UserModuleAccess.AccessType accessType,
            String grantedBy,
            String remark);

    void clearOverride(String factoryId, String userId, String moduleCode);
}
