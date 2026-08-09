package com.cretas.aims.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MaterialShortCodeRetirementMigrationContractTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/flyway/V20261029_79__decouple_material_taxonomy_from_business_code.sql");

    @Test
    void migrationExtractsTaxonomyThenRetiresEveryDualAndSixteenDigitCodePath() throws IOException {
        String sql = Files.readString(MIGRATION);

        assertThat(sql).contains("classification_segment_code = SUBSTRING(code FROM 1 FOR 10)");
        assertThat(sql.indexOf("classification_segment_code = SUBSTRING"))
                .isLessThan(sql.indexOf("UPDATE raw_material_types material"));
        assertThat(sql).contains("WHERE code ~ '^[0-9]{16}$'");
        assertThat(sql).contains("RAISE EXCEPTION 'Cannot retire 16-digit material codes");
        assertThat(sql).contains("DROP TABLE IF EXISTS material_business_code_counters");
        assertThat(sql).contains("DROP TABLE IF EXISTS material_business_code_prefixes");
        assertThat(sql).contains("DROP COLUMN IF EXISTS business_code");
        assertThat(sql).contains("ADD COLUMN IF NOT EXISTS classification_segment_id BIGINT");
        assertThat(sql).contains("ADD COLUMN IF NOT EXISTS parent_id BIGINT");
        assertThat(sql).contains("DROP COLUMN IF EXISTS parent_code");
        assertThat(sql).contains("DROP COLUMN IF EXISTS segment_code");
        assertThat(sql).contains("DROP COLUMN IF EXISTS classification_segment_code");
        assertThat(sql).contains("FOREIGN KEY (factory_id, classification_segment_id)");
        assertThat(sql).doesNotContain("ELSE 'WL'");
    }
}
