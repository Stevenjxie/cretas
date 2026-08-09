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

        assertThat(sql).contains("SET normalized_label = LOWER(REGEXP_REPLACE(TRIM(segment_label)");
        assertThat(sql).contains("HAVING COUNT(*) > 1");
        assertThat(sql).contains("STRING_AGG(id::TEXT || ':' || segment_label");
        assertThat(sql).contains("Cannot simplify material taxonomy: duplicate active category identity exists");
        assertThat(sql).contains("Cannot simplify material taxonomy: active category has no normalized identity");
        assertThat(sql.indexOf("SET normalized_label = LOWER"))
                .isLessThan(sql.indexOf("DROP COLUMN IF EXISTS parent_code"));
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
