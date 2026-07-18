package com.cretas.aims.migration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyBomItemsRemovalMigrationContractTest {

    @Test
    void clearsAuthorizedTestChainsBeforeDroppingLegacyBomTable() throws Exception {
        String sql = read("db/flyway/V20261028_76__drop_legacy_bom_items.sql");

        assertThat(sql)
                .contains("DELETE FROM factory_material_requisitions")
                .contains("DELETE FROM bom_change_logs")
                .contains("DELETE FROM bom_yield_suggestions")
                .contains("ADD COLUMN IF NOT EXISTS bom_recipe_id")
                .contains("ADD COLUMN IF NOT EXISTS bom_recipe_item_id")
                .contains("REFERENCES bom_recipes(id) ON DELETE SET NULL")
                .contains("REFERENCES bom_recipe_items(id) ON DELETE SET NULL")
                .contains("DROP TABLE IF EXISTS bom_items");

        assertThat(sql.indexOf("DELETE FROM factory_material_requisitions"))
                .isLessThan(sql.indexOf("DROP TABLE IF EXISTS bom_items"));
    }

    private String read(String resource) throws Exception {
        try (var input = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            assertThat(input).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
