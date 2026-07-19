package com.cretas.aims.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BomRecipeCanonicalUnitMigrationContractTest {

    @Test
    void removesLegacyStaticWhitelistInFavorOfUnitContractService() throws IOException {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/flyway/V20260720_03__bom_recipe_item_canonical_units.sql"));

        assertThat(sql).containsIgnoringCase("DROP CONSTRAINT IF EXISTS chk_bri_unit");
        assertThat(sql).doesNotContainIgnoringCase("ADD CONSTRAINT chk_bri_unit");
        assertThat(sql).contains("UnitContractService");
    }
}
