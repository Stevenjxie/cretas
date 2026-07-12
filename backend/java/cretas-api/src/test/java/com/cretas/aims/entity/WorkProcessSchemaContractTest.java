package com.cretas.aims.entity;

import jakarta.persistence.Column;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkProcessSchemaContractTest {

    private static final String MIGRATION_RESOURCE =
            "/db/flyway/V20261028_51__work_process_default_output_material_kind.sql";

    @Test
    void migrationGuardsEveryWorkProcessesMutationForFreshDatabases() throws IOException {
        String sql = readMigration();
        String guard = "IF to_regclass('public.work_processes') IS NOT NULL THEN";
        int guardStart = sql.indexOf(guard);

        assertTrue(guardStart >= 0, "migration must guard work_processes with to_regclass");

        int branchEnd = sql.indexOf("END IF;", guardStart);
        assertTrue(branchEnd > guardStart, "guarded branch must have an END IF");

        String guardedBranch = sql.substring(guardStart, branchEnd);
        assertTrue(guardedBranch.contains("ALTER TABLE work_processes"));
        assertTrue(guardedBranch.contains("UPDATE work_processes"));
        assertTrue(guardedBranch.contains("SET DEFAULT 'SEMI_FINISHED'"));
        assertTrue(guardedBranch.contains("SET NOT NULL"));
        assertTrue(guardedBranch.contains("CHECK (default_output_material_kind IN ('SEMI_FINISHED', 'FINISHED_GOOD'))"));

        String outsideGuard = sql.substring(0, guardStart)
                + sql.substring(branchEnd + "END IF;".length());
        assertFalse(outsideGuard.contains("ALTER TABLE work_processes"),
                "all work_processes ALTER statements must stay inside the guard");
        assertFalse(outsideGuard.contains("UPDATE work_processes"),
                "all work_processes UPDATE statements must stay inside the guard");
    }

    @Test
    void entityFreshSchemaDefinesDefaultNotNullAndAllowedValuesCheck() throws NoSuchFieldException {
        Field field = WorkProcess.class.getDeclaredField("defaultOutputMaterialKind");
        Column column = field.getAnnotation(Column.class);

        assertNotNull(column);
        assertFalse(column.nullable(), "fresh schema column must be NOT NULL");

        String definition = column.columnDefinition().toUpperCase();
        assertTrue(definition.contains("DEFAULT 'SEMI_FINISHED'"),
                "fresh schema column must default to SEMI_FINISHED");
        assertTrue(definition.contains("CHECK"),
                "fresh schema column must have an allowed-values CHECK");
        assertTrue(definition.contains("SEMI_FINISHED"));
        assertTrue(definition.contains("FINISHED_GOOD"));
    }

    private String readMigration() throws IOException {
        try (InputStream input = getClass().getResourceAsStream(MIGRATION_RESOURCE)) {
            assertNotNull(input, "migration resource must exist");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
