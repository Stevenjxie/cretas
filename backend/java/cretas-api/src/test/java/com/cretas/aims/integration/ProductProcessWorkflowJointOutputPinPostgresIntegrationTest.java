package com.cretas.aims.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in PostgreSQL proof that every output of a joint-production report can retain the
 * production plan's exact Workflow snapshot, even when an output SKU is not the Workflow owner.
 */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class ProductProcessWorkflowJointOutputPinPostgresIntegrationTest {

    private static final String SCHEMA_PREFIX = "workflow_verify_joint_output_";

    @Test
    void onePlanWorkflowSnapshotPinsEveryJointOutputBatch() throws Exception {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(
                requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        String username = requiredEnv("CRETAS_WORKFLOW_PG_USER");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        String schema = SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");

        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(true);
            statement.execute("CREATE SCHEMA " + schema);
            statement.execute("SET search_path TO " + schema);
            statement.execute("""
                    CREATE TABLE product_process_workflows (
                      id BIGINT PRIMARY KEY, factory_id VARCHAR(64) NOT NULL,
                      product_type_id VARCHAR(64) NOT NULL, definition_version INTEGER NOT NULL)
                    """);
            statement.execute("""
                    CREATE TABLE production_batches (
                      id BIGINT PRIMARY KEY, factory_id VARCHAR(64) NOT NULL,
                      product_type_id VARCHAR(64) NOT NULL, production_plan_id VARCHAR(191))
                    """);
            statement.execute("""
                    CREATE TABLE production_plans (
                      id VARCHAR(191) PRIMARY KEY, factory_id VARCHAR(64) NOT NULL,
                      product_type_id VARCHAR(64) NOT NULL)
                    """);
            statement.execute("""
                    CREATE TABLE work_process_tasks (
                      id BIGINT PRIMARY KEY, factory_id VARCHAR(64) NOT NULL,
                      product_work_process_id BIGINT NOT NULL, deleted_at TIMESTAMP)
                    """);

            executeMigration(connection,
                    "V20261028_52__product_process_workflow_runtime.sql");
            executeMigration(connection,
                    "V20261028_53__pin_batch_workflow_selection.sql");
            executeMigration(connection,
                    "V20261028_65__production_plan_workflow_snapshot.sql");
            executeMigration(connection,
                    "V20261028_83__allow_joint_output_batch_workflow_pin.sql");

            statement.execute("""
                    INSERT INTO product_process_workflows
                      (id, factory_id, product_type_id, definition_version)
                    VALUES (104, 'F006', 'PRODUCT-G', 2)
                    """);
            statement.execute("""
                    INSERT INTO production_plans
                      (id, factory_id, product_type_id, workflow_selection_mode,
                       selected_workflow_id, selected_workflow_version)
                    VALUES ('PLAN-JOINT', 'F006', 'PRODUCT-G', 'WORKFLOW', 104, 2)
                    """);

            statement.execute("""
                    INSERT INTO production_batches
                      (id, factory_id, product_type_id, production_plan_id)
                    VALUES (1001, 'F006', 'PRODUCT-G', 'PLAN-JOINT')
                    """);
            statement.execute("""
                    INSERT INTO production_batches
                      (id, factory_id, product_type_id, production_plan_id)
                    VALUES (1002, 'F006', 'PRODUCT-H', 'PLAN-JOINT')
                    """);

            assertEquals(2, scalarInt(connection, """
                    SELECT count(*) FROM production_batches
                     WHERE factory_id = 'F006'
                       AND workflow_selection_mode = 'WORKFLOW'
                       AND selected_workflow_id = 104
                       AND selected_workflow_version = 2
                    """));
            assertEquals("PRODUCT-H", scalarString(connection, """
                    SELECT product_type_id FROM production_batches WHERE id = 1002
                    """));

            SQLException wrongVersion = assertThrows(SQLException.class, () -> statement.execute("""
                    INSERT INTO production_plans
                      (id, factory_id, product_type_id, workflow_selection_mode,
                       selected_workflow_id, selected_workflow_version)
                    VALUES ('PLAN-WRONG-VERSION', 'F006', 'PRODUCT-G', 'WORKFLOW', 104, 99)
                    """));
            assertEquals("23503", wrongVersion.getSQLState());
        } finally {
            dropSchema(url, username, password, schema);
        }
    }

    private void executeMigration(Connection connection, String filename) throws Exception {
        String sql = new ClassPathResource("db/flyway/" + filename)
                .getContentAsString(StandardCharsets.UTF_8);
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private int scalarInt(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private String scalarString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private void dropSchema(String url, String username, String password, String schema)
            throws SQLException {
        if (!schema.startsWith(SCHEMA_PREFIX)) {
            throw new IllegalArgumentException("Refusing to drop unscoped schema: " + schema);
        }
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    name + " must be set when PostgreSQL verification is enabled");
        }
        return value;
    }
}
