package com.cretas.aims.migration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowPortSelectionGroupMigrationContractTest {

    @Test
    void migrationAddsCompleteSelectionGroupSnapshotAndConstraints() throws Exception {
        String sql = read("db/flyway/V20261028_72__workflow_port_selection_groups.sql");

        assertThat(sql)
                .contains("ADD COLUMN selection_group_id VARCHAR(128)")
                .contains("ADD COLUMN selection_group_label VARCHAR(255)")
                .contains("ADD COLUMN selection_group_mode VARCHAR(32)")
                .contains("ADD COLUMN selection_group_min_selections INTEGER")
                .contains("ADD COLUMN selection_group_max_selections INTEGER")
                .contains("'ALL_REQUIRED', 'EXACTLY_ONE', 'AT_LEAST_ONE', 'OPTIONAL'")
                .contains("selection_group_min_selections >= 0")
                .contains("selection_group_max_selections >= selection_group_min_selections");
    }

    private String read(String resource) throws Exception {
        try (var input = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            assertThat(input).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
