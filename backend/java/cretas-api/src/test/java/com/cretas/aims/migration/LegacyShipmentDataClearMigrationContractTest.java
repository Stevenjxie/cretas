package com.cretas.aims.migration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyShipmentDataClearMigrationContractTest {

    @Test
    void locksAndMatchesTheFrozenSnapshotBeforeDeletingOnlyAuthorizedTestRows() throws Exception {
        String sql = read("db/flyway/V20261028_81__clear_frozen_legacy_shipment_test_data.sql");

        assertThat(sql)
                .contains("LOCK TABLE public.shipment_records IN ACCESS EXCLUSIVE MODE")
                .contains("total_rows <> 64 OR live_rows <> 56 OR soft_deleted_rows <> 8")
                .contains("92e9ccab1c78eb13feb1239ac748df7d")
                .contains("'DEMO_FACTORY'::VARCHAR, 27::BIGINT, 27::BIGINT")
                .contains("'DEMO_FACTORY2'::VARCHAR, 1::BIGINT, 1::BIGINT")
                .contains("'F001'::VARCHAR, 27::BIGINT, 27::BIGINT")
                .contains("'F006'::VARCHAR, 8::BIGINT, 0::BIGINT")
                .contains("'FOOD_3101_048'::VARCHAR, 1::BIGINT, 1::BIGINT")
                .contains("confrelid = 'public.shipment_records'::REGCLASS")
                .contains("DELETE FROM public.shipment_records")
                .contains("GET DIAGNOSTICS deleted_rows = ROW_COUNT")
                .doesNotContainIgnoringCase("DROP TABLE")
                .doesNotContainIgnoringCase(" CASCADE");

        assertThat(sql.indexOf("LOCK TABLE public.shipment_records"))
                .isLessThan(sql.indexOf("snapshot_checksum IS DISTINCT FROM"));
        assertThat(sql.indexOf("snapshot_checksum IS DISTINCT FROM"))
                .isLessThan(sql.indexOf("DELETE FROM public.shipment_records"));
        assertThat(sql.indexOf("DELETE FROM public.shipment_records"))
                .isLessThan(sql.indexOf("GET DIAGNOSTICS deleted_rows = ROW_COUNT"));
    }

    private String read(String resource) throws Exception {
        try (var input = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            assertThat(input).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
