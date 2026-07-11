package com.cretas.aims.entity;

import com.cretas.aims.entity.workflow.ProductionWorkflowInstance;
import jakarta.persistence.Column;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductProcessWorkflowRuntimeSchemaContractTest {

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

    private String normalizedMigration() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/flyway/"
                + "V20261027_55__product_process_workflow_runtime.sql"));
        return sql.replaceAll("\\s+", " ").trim();
    }

    private void assertContains(String sql, String contract, String message) {
        assertTrue(sql.contains(contract), message + "; missing SQL: " + contract);
    }
}
