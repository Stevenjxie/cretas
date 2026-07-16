package com.cretas.aims.migration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PlanUnitSnapshotsMigrationContractTest {

    @Test
    void requisitionBackfillSkipsFreshDatabaseUntilHibernateCreatesItsTable() throws Exception {
        String sql = read("db/flyway/V20261028_70__plan_unit_snapshots_and_draft_purchase_supplier.sql");

        assertThat(sql)
                .contains("IF to_regclass('public.factory_material_requisitions') IS NULL THEN")
                .contains("RETURN;")
                .contains("ALTER TABLE factory_material_requisitions")
                .contains("UPDATE factory_material_requisitions requisition");
    }

    private String read(String resource) throws Exception {
        try (var input = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            assertThat(input).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
