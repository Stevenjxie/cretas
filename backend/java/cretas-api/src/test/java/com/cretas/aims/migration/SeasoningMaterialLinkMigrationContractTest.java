package com.cretas.aims.migration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class SeasoningMaterialLinkMigrationContractTest {

    @Test
    void earlyBackfillDefersWhenSeasoningTableDoesNotExistYet() throws Exception {
        String sql = read("db/flyway/V20260714_01__seasoning_material_link.sql");

        assertThat(sql)
                .contains("IF to_regclass('public.bom_seasoning_items') IS NULL THEN")
                .contains("RETURN;")
                .contains("deferred to V20261027_12");
    }

    @Test
    void laterCreateOwnsTheCompleteMaterialLinkDefinition() throws Exception {
        String sql = read("db/flyway/V20261027_12__bom_seasoning_items_and_pot_columns.sql");

        assertThat(sql)
                .contains("material_type_id   VARCHAR(191)")
                .contains("idx_bsi_material_type")
                .contains("CONSTRAINT fk_bsi_material_type")
                .contains("REFERENCES raw_material_types(id)");
    }

    private String read(String resource) throws Exception {
        try (var input = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            assertThat(input).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
