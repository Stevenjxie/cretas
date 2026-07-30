package com.cretas.aims.migration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class SupplierShortNameUniquenessMigrationContractTest {

    private static final String MIGRATION =
            "db/flyway/V20261029_35__supplier_short_name_unique.sql";

    @Test
    void createsTheRequiredFactoryScopedCaseInsensitivePartialUniqueIndex() throws Exception {
        String sql = readMigration();

        assertThat(sql)
                .contains("CREATE UNIQUE INDEX IF NOT EXISTS uq_suppliers_short_name")
                .contains("ON suppliers(factory_id, lower(short_name))")
                .contains("WHERE deleted_at IS NULL AND short_name IS NOT NULL");
    }

    @Test
    void reportsExistingDuplicatesInsteadOfSilentlyMutatingBusinessData() throws Exception {
        String sql = readMigration();

        assertThat(sql)
                .contains("GROUP BY factory_id, lower(short_name)")
                .contains("HAVING count(*) > 1")
                .contains("无法启用供应商简称唯一约束：存在重复简称")
                .contains("请先由业务负责人确认并修改重复供应商简称");
        assertThat(sql).doesNotContain("UPDATE suppliers");
        assertThat(sql).doesNotContain("DELETE FROM suppliers");
    }

    @Test
    void usesTheSameLowercaseIdentityAsTheServicePreflight() throws Exception {
        String sql = readMigration();
        String service = readResourceSource(
                "src/main/java/com/cretas/aims/service/impl/SupplierServiceImpl.java");

        assertThat(sql).contains("lower(short_name)");
        assertThat(service)
                .contains("normalizeShortName")
                .contains("trimmed.toLowerCase(Locale.ROOT)");
    }

    private String readMigration() throws Exception {
        try (var input = Thread.currentThread().getContextClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(input).as("找不到 migration: " + MIGRATION).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String readResourceSource(String path) throws Exception {
        return java.nio.file.Files.readString(java.nio.file.Path.of(path), StandardCharsets.UTF_8);
    }
}
