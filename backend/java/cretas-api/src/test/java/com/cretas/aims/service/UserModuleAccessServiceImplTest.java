package com.cretas.aims.service;

import com.cretas.aims.entity.User;
import com.cretas.aims.entity.UserModuleAccess;
import com.cretas.aims.repository.UserModuleAccessRepository;
import com.cretas.aims.service.impl.UserModuleAccessServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserModuleAccessServiceImplTest {

    @Mock
    private UserModuleAccessRepository repository;

    @Mock
    private PermissionService permissionService;

    @Test
    @DisplayName("DENY override blocks module even when role default allows it")
    void denyOverrideWinsOverRoleDefault() {
        User user = user(1309L, "F006", "factory_super_admin");
        when(repository.findActive("F006", "1309", "production_plan"))
                .thenReturn(Optional.of(access("F006", "1309", "production_plan", UserModuleAccess.AccessType.DENY)));

        UserModuleAccessServiceImpl service = new UserModuleAccessServiceImpl(repository, permissionService);

        assertFalse(service.canAccessModule(user, "production_plan"));
    }

    @Test
    @DisplayName("GRANT override allows module even when role default denies it")
    void grantOverrideWinsOverRoleDefault() {
        User user = user(88L, "F006", "viewer");
        when(repository.findActive("F006", "88", "system"))
                .thenReturn(Optional.of(access("F006", "88", "system", UserModuleAccess.AccessType.GRANT)));

        UserModuleAccessServiceImpl service = new UserModuleAccessServiceImpl(repository, permissionService);

        assertTrue(service.canAccessModule(user, "system"));
    }

    @Test
    @DisplayName("Missing user override falls back to existing L2/L1 role-module resolver")
    void missingOverrideFallsBackToRoleDefault() {
        User user = user(42L, "F006", "warehouse_manager");
        when(repository.findActive("F006", "42", "warehouse")).thenReturn(Optional.empty());
        when(permissionService.hasAnyPermission(
                user,
                "warehouse:read",
                "warehouse:write",
                "warehouse:read_write")).thenReturn(true);

        UserModuleAccessServiceImpl service = new UserModuleAccessServiceImpl(repository, permissionService);

        assertTrue(service.canAccessModule(user, "warehouse"));
    }

    private static User user(Long id, String factoryId, String roleCode) {
        User user = new User();
        user.setId(id);
        user.setFactoryId(factoryId);
        user.setRoleCode(roleCode);
        return user;
    }

    private static UserModuleAccess access(
            String factoryId,
            String userId,
            String moduleCode,
            UserModuleAccess.AccessType accessType) {
        UserModuleAccess access = new UserModuleAccess();
        access.setFactoryId(factoryId);
        access.setUserId(userId);
        access.setModuleCode(moduleCode);
        access.setAccessType(accessType);
        return access;
    }
}
