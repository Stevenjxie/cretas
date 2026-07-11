package com.cretas.aims.integration;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.repository.ProductionBatchRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Opt-in proof that Hibernate rereads the three database-generated pin columns. */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class ProductProcessWorkflowBatchPinRepositoryPostgresIntegrationTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        String safeUrl = DisposablePostgresTargetGuard.requireSafeUrl(
                requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        registry.add("spring.datasource.url", () -> safeUrl);
        registry.add("spring.datasource.username", () -> requiredEnv("CRETAS_WORKFLOW_PG_USER"));
        registry.add("spring.datasource.password",
                () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ProductionBatchRepository batchRepository;

    @Test
    void saveAndFlushReturnsTriggerGeneratedWorkflowPin() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String factory = "WF-PIN-" + suffix;
        String product = "PRODUCT-" + suffix;

        jdbcTemplate.update("""
                INSERT INTO product_process_workflows
                  (factory_id, product_type_id, schema_version, status, definition_version,
                   nodes_json, edges_json, viewport_json, lock_version, created_at, updated_at)
                VALUES (?, ?, 1, 'PUBLISHED', 7, '[]', '[]',
                        '{"x":0,"y":0,"zoom":1}', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, factory, product);
        Long workflowId = jdbcTemplate.queryForObject("""
                SELECT id FROM product_process_workflows
                 WHERE factory_id = ? AND product_type_id = ? AND definition_version = 7
                """, Long.class, factory, product);
        assertNotNull(workflowId);
        jdbcTemplate.update("""
                INSERT INTO product_process_workflow_activations
                  (factory_id, product_type_id, active_workflow_id,
                   active_definition_version, enabled, activated_at, lock_version,
                   created_at, updated_at)
                VALUES (?, ?, ?, 7, TRUE, CURRENT_TIMESTAMP, 0,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, factory, product, workflowId);

        installBatchPinTrigger();

        ProductionBatch batch = new ProductionBatch();
        batch.setFactoryId(factory);
        batch.setProductTypeId(product);
        batch.setBatchNumber("BATCH-" + suffix);
        batch.setQuantity(BigDecimal.ONE);
        batch.setPlannedQuantity(BigDecimal.ONE);
        batch.setUnit("kg");

        ProductionBatch saved = batchRepository.saveAndFlush(batch);

        assertEquals(ProductionBatch.WorkflowSelectionMode.WORKFLOW,
                saved.getWorkflowSelectionMode());
        assertEquals(workflowId, saved.getSelectedWorkflowId());
        assertEquals(7, saved.getSelectedWorkflowVersion());
    }

    private void installBatchPinTrigger() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_pin_production_batch_workflow_selection ON production_batches");
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION pin_production_batch_workflow_selection()
                RETURNS TRIGGER
                LANGUAGE plpgsql
                AS $$
                DECLARE
                  pinned_workflow_id BIGINT;
                  pinned_definition_version INTEGER;
                BEGIN
                  PERFORM pg_advisory_xact_lock(hashtextextended(
                    NEW.factory_id || E'\\x1f' || NEW.product_type_id, 0));
                  SELECT active_workflow_id, active_definition_version
                    INTO pinned_workflow_id, pinned_definition_version
                    FROM product_process_workflow_activations
                   WHERE factory_id = NEW.factory_id
                     AND product_type_id = NEW.product_type_id
                     AND enabled = TRUE
                     AND deleted_at IS NULL;
                  IF FOUND THEN
                    NEW.workflow_selection_mode := 'WORKFLOW';
                    NEW.selected_workflow_id := pinned_workflow_id;
                    NEW.selected_workflow_version := pinned_definition_version;
                  ELSE
                    NEW.workflow_selection_mode := 'LEGACY';
                    NEW.selected_workflow_id := NULL;
                    NEW.selected_workflow_version := NULL;
                  END IF;
                  RETURN NEW;
                END;
                $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER trg_pin_production_batch_workflow_selection
                BEFORE INSERT ON production_batches
                FOR EACH ROW
                EXECUTE FUNCTION pin_production_batch_workflow_selection()
                """);
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set when PostgreSQL verification is enabled");
        }
        return value;
    }
}
