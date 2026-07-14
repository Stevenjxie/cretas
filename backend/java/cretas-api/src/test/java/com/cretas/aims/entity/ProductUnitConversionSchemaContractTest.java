package com.cretas.aims.entity;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductUnitConversionSchemaContractTest {

    private static final Path MIGRATION = Path.of(
            "src", "main", "resources", "db", "flyway",
            "V20261028_62__product_unit_conversions.sql");
    private static final Path ENTITY = Path.of(
            "src", "main", "java", "com", "cretas", "aims", "entity", "unit",
            "ProductUnitConversion.java");
    private static final Path REPOSITORY = Path.of(
            "src", "main", "java", "com", "cretas", "aims", "repository", "unit",
            "ProductUnitConversionRepository.java");

    @Test
    void productUnitConversionSchemaDefinesAuthorityAndActiveDirectionContract() throws Exception {
        assertTrue(Files.exists(MIGRATION), "Task 2 Flyway migration must exist");

        String sql = Files.readString(MIGRATION);
        assertContains(sql, "CREATE TABLE product_unit_conversions");
        assertContains(sql, "factor NUMERIC(20,8) NOT NULL CHECK (factor > 0)");
        assertContains(sql, "CHECK (source_type IN ('NET_CONTENT','PACKAGING','MANUAL'))");
        assertContains(sql, "CHECK (from_unit_code <> to_unit_code)");
        assertContains(sql, "CHECK (effective_to IS NULL OR effective_to > effective_from)");
        assertContains(sql, "CREATE UNIQUE INDEX uq_puc_active_direction");
        assertContains(sql, "ON product_unit_conversions(factory_id, product_type_id, from_unit_code, to_unit_code)");
        assertContains(sql, "WHERE deleted_at IS NULL AND effective_to IS NULL");
        assertContains(sql, "ALTER TABLE unit_of_measurements");
        assertContains(sql, "ADD COLUMN aliases_json JSONB");

        assertTrue(Files.exists(ENTITY), "Task 2 product conversion entity must exist");
        String entity = Files.readString(ENTITY);
        assertContains(entity, "extends BaseEntity");
        assertContains(entity, "@Version");
        assertContains(entity, "enum SourceType");
        assertContains(entity, "NET_CONTENT");
        assertContains(entity, "PACKAGING");
        assertContains(entity, "MANUAL");

        assertTrue(Files.exists(REPOSITORY), "Task 2 product conversion repository must exist");
        String repository = Files.readString(REPOSITORY);
        assertContains(repository, "findEffectiveByFactoryIdAndProductTypeIdAt");
        assertContains(repository, "p.effectiveFrom <= :at");
        assertContains(repository, "p.effectiveTo IS NULL OR p.effectiveTo > :at");
        assertContains(repository, "p.deletedAt IS NULL");
    }

    private static void assertContains(String value, String expected) {
        assertTrue(value.contains(expected), () -> "Expected contract fragment: " + expected);
    }
}
