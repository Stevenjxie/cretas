package com.cretas.aims.migration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class MaterialPackagingSpecsMigrationContractTest {

    @Test
    void migrationCreatesDirectRulesAndBackfillsBothLegacyPackagingLevels() throws Exception {
        String sql = read("db/flyway/V20261029_22__material_packaging_specs.sql");

        assertThat(sql)
                .contains("CREATE TABLE material_packaging_specs")
                .contains("conversion_factor NUMERIC(20,8) NOT NULL CHECK (conversion_factor > 0)")
                .contains("uq_material_packaging_specs_default")
                .contains("uq_material_packaging_specs_unit")
                .contains("h.level1_per_level2, TRUE, TRUE, 0")
                .contains("h.level1_per_level2 * h.level2_per_level3, FALSE, TRUE, 1");
    }

    private String read(String resource) throws Exception {
        try (var input = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            assertThat(input).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
