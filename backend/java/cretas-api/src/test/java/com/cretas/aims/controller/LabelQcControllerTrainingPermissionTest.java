package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
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
}
