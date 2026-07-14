package com.cretas.aims.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Opt-in PostgreSQL proof for the Flyway-only partial unique index.
 *
 * <p>Run only with a disposable local database:
 * {@code CRETAS_WORKFLOW_PG_VERIFY=true}, {@code CRETAS_WORKFLOW_PG_URL}, and
 * {@code CRETAS_WORKFLOW_PG_USER}. The URL guard rejects shared and remote targets.</p>
 */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class ProductUnitConversionPostgresIntegrationTest {

    @Test
    void activeDuplicateFailsWhileSoftDeletedPredecessorDoesNotBlockReplacement() throws Exception {
        String schema = "unit_conversion_" + UUID.randomUUID().toString().replace("-", "");

        try (Connection connection = DriverManager.getConnection(
                DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL")),
                requiredEnv("CRETAS_WORKFLOW_PG_USER"),
                System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(true);
            statement.execute("CREATE SCHEMA " + schema);
            statement.execute("SET search_path TO " + schema);
            statement.execute("CREATE TABLE unit_of_measurements (id VARCHAR(36) PRIMARY KEY)");
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/flyway/V20261028_62__product_unit_conversions.sql"));

            insertActiveConversion(statement, "first");
            SQLException duplicate = assertThrows(SQLException.class,
                    () -> insertActiveConversion(statement, "duplicate"));
            assertEquals("23505", duplicate.getSQLState());

            statement.execute("UPDATE product_unit_conversions SET deleted_at = CURRENT_TIMESTAMP WHERE id = 'first'");
            insertActiveConversion(statement, "replacement");
        } finally {
            dropSchema(schema);
        }
    }

    private static void insertActiveConversion(Statement statement, String id) throws SQLException {
        statement.execute("""
                INSERT INTO product_unit_conversions
                  (id, factory_id, product_type_id, from_unit_code, to_unit_code, factor, source_type)
                VALUES ('%s', 'F-UNIT', 'P-UNIT', 'box', 'kg', 12.50000000, 'PACKAGING')
                """.formatted(id));
    }

    private static void dropSchema(String schema) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL")),
                requiredEnv("CRETAS_WORKFLOW_PG_USER"),
                System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set when PostgreSQL verification is enabled");
        }
        return value;
    }
}
