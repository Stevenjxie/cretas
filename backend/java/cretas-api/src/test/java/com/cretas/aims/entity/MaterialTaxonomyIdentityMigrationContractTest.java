package com.cretas.aims.entity;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaterialTaxonomyIdentityMigrationContractTest {

    @Test
    void migrationQuarantinesHistoricalConflictsWithoutRewritingTaxonomy() throws Exception {
        ClassPathResource migration = new ClassPathResource(
                "db/flyway/V20261028_92__material_taxonomy_identity.sql");
        String sql = new String(migration.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                .toLowerCase(Locale.ROOT);

        assertTrue(sql.contains("having count(*) > 1"));
        assertTrue(sql.contains("set normalized_label = null"));
        assertTrue(sql.contains("normalized_label is not null"));
        assertTrue(sql.contains("where deleted_at is null"));

        assertFalse(sql.contains("alter column normalized_label set not null"));
        assertFalse(sql.contains("delete from material_code_segments"));
        assertFalse(sql.matches("(?s).*set\\s+segment_label\\s*=.*"));
        assertFalse(sql.matches("(?s).*set\\s+parent_code\\s*=.*"));
        assertFalse(sql.matches("(?s).*set\\s+segment_code\\s*=.*"));
    }
}
