package com.cretas.aims.migration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class LogisticsDemoVehicleMigrationContractTest {

    @Test
    void demoSeedSkipsFreshDatabasesWithoutHibernateOwnedVehiclesTable() throws Exception {
        String resource = "db/flyway/V20261028_04__logistics_demo_base_vehicles.sql";
        try (var input = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sql)
                    .contains("IF to_regclass('public.vehicles') IS NULL THEN")
                    .contains("RETURN;")
                    .contains("ON CONFLICT (id) DO NOTHING");
        }
    }
}
