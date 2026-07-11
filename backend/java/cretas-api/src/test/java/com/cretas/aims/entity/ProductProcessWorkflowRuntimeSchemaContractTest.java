package com.cretas.aims.entity;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductProcessWorkflowRuntimeSchemaContractTest {

    @Test
    void migrationDefinesRuntimeOwnershipAndLegacyFallbackContracts() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/flyway/"
                + "V20261027_55__product_process_workflow_runtime.sql"));
        assertTrue(sql.contains("UNIQUE (factory_id, product_type_id)"));
        assertTrue(sql.contains("UNIQUE (factory_id, production_batch_id)"));
        assertTrue(sql.contains("CREATE UNIQUE INDEX uk_workflow_task_node"));
        assertTrue(sql.contains("ON work_process_tasks(workflow_instance_id, workflow_node_id)"));
        assertTrue(sql.contains("UNIQUE (task_id, workflow_port_id)"));
        assertTrue(sql.contains("ALTER COLUMN product_work_process_id DROP NOT NULL"));
    }
}
