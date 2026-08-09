package com.cretas.aims.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("无订单入库申请迁移契约")
class UnorderedInboundNoticeMigrationContractTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/flyway/V20261029_76__unordered_inbound_notice_reason.sql");

    @Test
    @DisplayName("旧客户来料默认不漂移，客户来料仍必须有客户，赠予和其他可无客户")
    void migrationPreservesOldRowsAndConstrainsOwnershipSource() throws IOException {
        String sql = Files.readString(MIGRATION);

        assertThat(sql).contains("NOT NULL DEFAULT 'CUSTOMER_MATERIAL'");
        assertThat(sql).contains("ALTER COLUMN customer_id DROP NOT NULL");
        assertThat(sql).contains("'CUSTOMER_MATERIAL', 'GIFT', 'OTHER'");
        assertThat(sql).contains("inbound_reason <> 'CUSTOMER_MATERIAL' OR customer_id IS NOT NULL");
        assertThat(sql).doesNotContain("UPDATE customer_material_arrival_notices");
    }
}
