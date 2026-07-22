package com.cretas.aims.migration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class F006SalesOrderApprovalWorkflowMigrationContractTest {

    private static final String MIGRATION =
            "db/flyway/V20261029_01__seed_f006_sales_order_approval_workflow.sql";

    @Test
    void migrationSeedsOnePublishedF006SalesWorkflowWithAuditedAutoAndFinancePaths() throws Exception {
        String sql = read(MIGRATION);
        String normalized = sql.toLowerCase(Locale.ROOT);

        assertThat(sql)
                .contains("'F006'")
                .contains("'SALES_ORDER_APPROVAL'")
                .contains("#externalOrder == true")
                .contains("#amount > 5000")
                .contains("finance_manager", "factory_super_admin")
                .contains("#decision == ''APPROVE''")
                .contains("#decision == ''REJECT''")
                .contains("'published', TRUE")
                .contains("WHERE NOT EXISTS")
                .contains("existing.publish_status = 'published'")
                .contains("existing.enabled = TRUE")
                .contains("ON CONFLICT (factory_id, decision_type, name) DO NOTHING");
        assertThat(normalized).containsOnlyOnce("insert into approval_workflows");
        assertThat(normalized)
                .doesNotContain("insert into sales_orders")
                .doesNotContain("update sales_orders")
                .doesNotContain("insert into approval_workflow_instances")
                .doesNotContain("delete from");
    }

    @Test
    void flywayVersionIsUniqueAcrossTheFullDirectory() throws Exception {
        Path flyway = Path.of("src/main/resources/db/flyway");
        try (var files = Files.list(flyway)) {
            assertThat(files
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("V20261029_01__")))
                    .containsExactly("V20261029_01__seed_f006_sales_order_approval_workflow.sql");
        }
    }

    private String read(String resource) throws Exception {
        try (var input = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            assertThat(input).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
