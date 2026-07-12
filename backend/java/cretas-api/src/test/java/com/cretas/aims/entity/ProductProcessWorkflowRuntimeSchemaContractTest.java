package com.cretas.aims.entity;

import com.cretas.aims.entity.workflow.ProductionWorkflowInstance;
import jakarta.persistence.Column;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductProcessWorkflowRuntimeSchemaContractTest {

    @Test
    void createsCompleteImmutableInstanceThroughNormalJavaApi() {
        LocalDateTime compiledAt = LocalDateTime.of(2026, 7, 11, 11, 15);

        ProductionWorkflowInstance instance = ProductionWorkflowInstance.create(
                "F006",
                101L,
                "PRODUCT-7",
                202L,
                3,
                "[{\"id\":\"process-1\"}]",
                "[{\"source\":\"start\",\"target\":\"process-1\"}]",
                compiledAt);

        assertEquals("F006", instance.getFactoryId());
        assertEquals(101L, instance.getProductionBatchId());
        assertEquals("PRODUCT-7", instance.getProductTypeId());
        assertEquals(202L, instance.getWorkflowId());
        assertEquals(3, instance.getDefinitionVersion());
        assertEquals("[{\"id\":\"process-1\"}]", instance.getNodesJson());
        assertEquals("[{\"source\":\"start\",\"target\":\"process-1\"}]", instance.getEdgesJson());
        assertEquals(compiledAt, instance.getCompiledAt());
        assertEquals(ProductionWorkflowInstance.Status.ACTIVE, instance.getStatus());
    }

    @Test
    void migrationDefinesRuntimeOwnershipAndLegacyFallbackContracts() throws Exception {
        String sql = normalizedMigration();
        assertContains(sql, "UNIQUE (factory_id, product_type_id)",
                "activation ownership must be unique per factory and product");
        assertContains(sql, "UNIQUE (factory_id, production_batch_id)",
                "instance ownership must be unique per factory and batch");
        assertContains(sql, "CREATE UNIQUE INDEX uk_workflow_task_node",
                "workflow nodes must spawn at most one live task per instance");
        assertContains(sql, "ON work_process_tasks(workflow_instance_id, workflow_node_id)",
                "task-node uniqueness must use runtime instance and node identity");
        assertContains(sql, "UNIQUE (task_id, workflow_port_id)",
                "workflow port identity must be unique within a task");
        assertContains(sql, "ALTER COLUMN product_work_process_id DROP NOT NULL",
                "runtime tasks must not require a legacy product-work-process row");
    }

    @Test
    void migrationEnforcesFactoryAndInstanceOwnershipAcrossRuntimeChain() throws Exception {
        String sql = normalizedMigration();

        assertContains(sql,
                "CONSTRAINT uk_ppw_id_factory_product_version UNIQUE (id, factory_id, product_type_id, definition_version)",
                "workflow must expose a stable activation ownership key");
        assertContains(sql,
                "CONSTRAINT uk_ppw_id_factory_product UNIQUE (id, factory_id, product_type_id)",
                "workflow must expose a stable instance ownership key");
        assertContains(sql,
                "CONSTRAINT fk_ppwa_active_workflow_owner FOREIGN KEY (active_workflow_id, factory_id, product_type_id, active_definition_version) REFERENCES product_process_workflows(id, factory_id, product_type_id, definition_version)",
                "activation must match workflow factory, product, and definition version");
        assertContains(sql,
                "CONSTRAINT uk_production_batch_runtime_owner UNIQUE (id, factory_id, product_type_id)",
                "batch must expose a stable runtime ownership key");
        assertContains(sql,
                "CONSTRAINT fk_pwi_batch_owner FOREIGN KEY (production_batch_id, factory_id, product_type_id) REFERENCES production_batches(id, factory_id, product_type_id)",
                "instance must match batch factory and product");
        assertContains(sql,
                "CONSTRAINT fk_pwi_workflow_owner FOREIGN KEY (workflow_id, factory_id, product_type_id) REFERENCES product_process_workflows(id, factory_id, product_type_id)",
                "instance must match workflow factory and product");
        assertContains(sql,
                "CONSTRAINT fk_wpt_workflow_instance_owner FOREIGN KEY (workflow_instance_id, factory_id) REFERENCES production_workflow_instances(id, factory_id)",
                "task must match runtime instance factory");
        assertContains(sql,
                "CONSTRAINT uk_pwi_id_factory UNIQUE (id, factory_id)",
                "runtime instance must expose a stable factory ownership key");
        assertContains(sql,
                "CONSTRAINT uk_wpt_id_factory_instance UNIQUE (id, factory_id, workflow_instance_id)",
                "task must expose its factory and workflow-instance ownership tuple");
        assertContains(sql,
                "CONSTRAINT fk_wtp_task_owner FOREIGN KEY (task_id, factory_id, workflow_instance_id) REFERENCES work_process_tasks(id, factory_id, workflow_instance_id)",
                "port task must belong to the same factory and workflow instance");
        assertContains(sql,
                "CONSTRAINT fk_wtp_workflow_instance_owner FOREIGN KEY (workflow_instance_id, factory_id) REFERENCES production_workflow_instances(id, factory_id)",
                "port must match runtime instance factory directly");
    }

    @Test
    void compiledInstanceIdentityAndSnapshotAreWriteOnceButStatusRemainsMutable() throws Exception {
        List<String> writeOnceFields = List.of(
                "factoryId",
                "productionBatchId",
                "productTypeId",
                "workflowId",
                "definitionVersion",
                "nodesJson",
                "edgesJson",
                "compiledAt");

        for (String fieldName : writeOnceFields) {
            Field field = ProductionWorkflowInstance.class.getDeclaredField(fieldName);
            Column column = field.getAnnotation(Column.class);
            assertNotNull(column, fieldName + " must declare its column contract");
            assertFalse(column.updatable(), fieldName + " must be write-once in Hibernate");

            String setterName = "set" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
            boolean hasPublicSetter = Arrays.stream(ProductionWorkflowInstance.class.getMethods())
                    .anyMatch(method -> method.getName().equals(setterName));
            assertFalse(hasPublicSetter, fieldName + " must not expose a public setter");
        }

        assertNotNull(ProductionWorkflowInstance.class.getMethod(
                "setStatus", ProductionWorkflowInstance.Status.class),
                "status must remain mutable as a lifecycle field");
    }

    @Test
    void v56PinsEveryBatchInsertToAnExactWorkflowOrExplicitLegacyDecision() throws Exception {
        String sql = normalizedMigration(
                "V20261028_53__pin_batch_workflow_selection.sql");

        assertContains(sql, "workflow_selection_mode VARCHAR(16) NOT NULL DEFAULT 'LEGACY'",
                "existing batches must backfill to an explicit legacy decision");
        assertContains(sql, "CONSTRAINT ck_production_batch_workflow_selection CHECK",
                "workflow mode must require both selected workflow fields");
        assertContains(sql, "CONSTRAINT fk_production_batch_selected_workflow FOREIGN KEY",
                "the batch pin must reference an owned exact workflow version");
        assertContains(sql, "BEFORE INSERT ON production_batches",
                "all production-batch creation paths must pass through one atomic pin");
        assertContains(sql,
                "BEFORE INSERT OR UPDATE OR DELETE ON product_process_workflow_activations",
                "every activation mutation must take the same product-scoped transaction lock");
        assertContains(sql, "lock_product_process_workflow_activation()",
                "activation writes must have a dedicated lock trigger function");
        assertTrue(occurrences(sql, "pg_advisory_xact_lock(hashtextextended(") >= 2,
                "both activation writes and batch inserts must take a 64-bit transaction lock");
        assertTrue(
                sql.indexOf("pg_advisory_xact_lock(hashtextextended(")
                        < sql.indexOf("SELECT activation.active_workflow_id"),
                "batch insertion must lock before deciding whether an activation exists");
        assertFalse(sql.contains("FOR SHARE"),
                "the advisory lock must not be followed by a reverse-order activation row lock");
        assertContains(sql, "NEW.workflow_selection_mode := 'WORKFLOW'",
                "enabled activation must pin workflow mode");
        assertContains(sql, "NEW.workflow_selection_mode := 'LEGACY'",
                "absence of an enabled activation must pin legacy mode");
    }

    @Test
    void batchPinColumnsAreDatabaseGeneratedReadOnlyAndFetchedAfterInsert() throws Exception {
        for (String fieldName : List.of(
                "workflowSelectionMode", "selectedWorkflowId", "selectedWorkflowVersion")) {
            Field field = ProductionBatch.class.getDeclaredField(fieldName);
            Column column = field.getAnnotation(Column.class);
            Generated generated = field.getAnnotation(Generated.class);

            assertNotNull(column, fieldName + " must declare its database column");
            assertFalse(column.insertable(), fieldName + " must be populated only by the insert trigger");
            assertFalse(column.updatable(), fieldName + " must remain immutable after batch creation");
            assertNotNull(generated, fieldName + " must be fetched back after the insert trigger runs");
            assertTrue(Arrays.asList(generated.event()).contains(EventType.INSERT),
                    fieldName + " must be generated on insert");
        }
    }

    private String normalizedMigration() throws Exception {
        return normalizedMigration("V20261028_52__product_process_workflow_runtime.sql");
    }

    private String normalizedMigration(String fileName) throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/flyway/" + fileName));
        return sql.replaceAll("\\s+", " ").trim();
    }

    private void assertContains(String sql, String contract, String message) {
        assertTrue(sql.contains(contract), message + "; missing SQL: " + contract);
    }

    private int occurrences(String value, String needle) {
        return (value.length() - value.replace(needle, "").length()) / needle.length();
    }
}
