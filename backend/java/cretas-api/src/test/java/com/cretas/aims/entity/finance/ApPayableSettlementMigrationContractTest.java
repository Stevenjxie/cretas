package com.cretas.aims.entity.finance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AP settlement Flyway legacy-data contract")
class ApPayableSettlementMigrationContractTest {

    private static final String MIGRATION =
            "db/flyway/V20261028_94__ap_payable_settlement_core.sql";

    @Test
    @DisplayName("historical AP invoices remain unknown and require reconciliation")
    void legacyInvoicesAreNotReopenedAsUnpaid() throws Exception {
        ClassPathResource resource = new ClassPathResource(MIGRATION);
        String sql = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(sql).contains("SET payment_status = 'NEEDS_RECONCILIATION'");
        assertThat(sql).contains("payment_status = 'NEEDS_RECONCILIATION'");
        assertThat(sql).contains("settled_amount IS NULL");
        assertThat(sql).contains("outstanding_amount IS NULL");
        assertThat(sql).doesNotContain("SET settled_amount = 0,\n    outstanding_amount = ABS(amount)");
    }
}
