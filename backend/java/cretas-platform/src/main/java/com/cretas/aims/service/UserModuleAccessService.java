package com.cretas.aims.service;

import com.cretas.aims.entity.User;
import com.cretas.aims.entity.UserModuleAccess;

import java.util.List;

public interface UserModuleAccessService {

    record ModuleAccessView(
            String moduleCode,
            String displayName,
            String category,
            String permissionModule,
            boolean roleDefaultAllowed,
            String override,
            boolean effectiveAllowed) {
    }

    boolean canAccessModule(User user, String moduleCode);

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
