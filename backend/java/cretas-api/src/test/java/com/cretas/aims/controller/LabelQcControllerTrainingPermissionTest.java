package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.config.RequireRole;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class LabelQcControllerTrainingPermissionTest {

    @Test
    void onlyTechnicalAdministratorsCanDecideOrExportTrainingData() {
        assertPermissions("decideTraining", Set.of("system:read_write"));
        assertPermissions("exportTrainingData", Set.of("system:read_write"));
    }

    @Test
    void qualityOrTechnicalAdministratorsCanArchiveRestoreAndBackupReviewedData() {
        Set<String> dataManagementPermissions =
                Set.of("quality:read_write", "system:read_write");
        assertPermissions("archive", dataManagementPermissions);
        assertPermissions("restore", dataManagementPermissions);
        assertPermissions("backup", dataManagementPermissions);
    }

    @Test
    void trayCropRefinementRequiresPlatformRoleAndSystemWritePermission() {
        Set<String> platformRoles = Set.of(
                "platform_admin", "super_admin", "developer", "platform_super_admin");
        assertPermissions("listTrayCrops", Set.of("system:read_write"));
        assertPermissions("reviewTrayCrop", Set.of("system:read_write"));
        assertRoles("listTrayCrops", platformRoles);
        assertRoles("reviewTrayCrop", platformRoles);
    }

    private void assertPermissions(String methodName, Set<String> expectedPermissions) {
        Method method = Arrays.stream(LabelQcController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        RequirePermission permission = method.getAnnotation(RequirePermission.class);

        assertNotNull(permission, methodName + " must declare an explicit permission gate");
        assertEquals(expectedPermissions, Set.of(permission.value()));
        assertFalse(permission.requireAll(), methodName + " should accept any listed role gate");
    }

    private void assertRoles(String methodName, Set<String> expectedRoles) {
        Method method = Arrays.stream(LabelQcController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        RequireRole role = method.getAnnotation(RequireRole.class);

        assertNotNull(role, methodName + " must declare a platform-only role gate");
        assertEquals(expectedRoles, Set.of(role.value()));
    }
}
