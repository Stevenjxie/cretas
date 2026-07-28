package com.cretas.aims.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionPlanBomAuthorityMigrationContractTest {

    @Test
    void pinsCompletePlanAuthorityIntoBatchesAndCreatesOutputLineLedger() throws IOException {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/flyway/"
                        + "V20261029_30__production_plan_bom_authority_and_output_lines.sql"));

        assertThat(sql).contains("UPDATE production_plans")
                .contains("workflow_selection_mode = 'LEGACY'")
                .contains("Preserve their mode and old selection")
                .contains("selected_workflow_revision_id")
                .contains("selected_workflow_revision_hash")
                .contains("selected_bom_family_id")
                .contains("selected_bom_recipe_ids_by_product")
                .contains("selected_bom_versions_by_product")
                .contains("workflow_output_units_by_product")
                .contains("target_finished_good_ids")
                .contains("ALTER COLUMN actual_finished_quantity DROP NOT NULL")
                .contains("ADD COLUMN IF NOT EXISTS product_type_id VARCHAR(191)")
                .contains("ADD COLUMN IF NOT EXISTS workflow_material_node_id VARCHAR(128)")
                .contains("ADD COLUMN IF NOT EXISTS workflow_input_port_id VARCHAR(128)")
                .contains("CREATE TABLE IF NOT EXISTS production_settlement_output_lines")
                .contains("reported_quantity NUMERIC(18,4) NOT NULL")
                .contains("received_quantity NUMERIC(18,4)")
                .contains("CREATE OR REPLACE FUNCTION pin_production_batch_workflow_selection()")
                .contains("FROM product_process_workflow_activations activation")
                .contains("is Workflow-governed; create the batch through a production plan")
                .contains("Expand-phase compatibility");
        assertThat(sql)
                .doesNotContain("WHERE workflow_selection_mode = 'WORKFLOW'")
                .doesNotContain("workflow production plan % lacks immutable revision/BOM authority")
                .doesNotContain("UPDATE production_batches");
    }
}
