package com.cretas.aims.migration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class SkuReportingMigrationContractTest {

    @Test
    void v68BackfillsOnlyUnambiguousActiveWorkflowAndPlanWeightSnapshots() throws Exception {
        String resource = "db/flyway/V20261028_68__sku_reporting_units_and_production_allocations.sql";
        try (var input = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sql).contains("pwi.status = 'ACTIVE'")
                    .contains("wtp.sku_id = pt.id")
                    .contains("pp.product_type_id = pt.id")
                    .contains("sku_unit_migration_issues")
                    .contains("NET_WEIGHT_SNAPSHOT_MISSING");
        }
    }
}
