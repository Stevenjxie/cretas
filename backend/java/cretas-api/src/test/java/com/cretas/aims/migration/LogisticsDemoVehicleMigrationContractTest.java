package com.cretas.aims.migration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class LogisticsDemoVehicleMigrationContractTest {

    @Test
    void demoSeedSkipsFreshDatabasesWithoutHibernateOwnedVehiclesTable() throws Exception {
        String sql = read("db/flyway/V20261028_04__logistics_demo_base_vehicles.sql");
        assertThat(sql)
                .contains("IF to_regclass('public.vehicles') IS NULL THEN")
                .contains("RETURN;")
                .contains("ON CONFLICT (id) DO NOTHING");
    }

    @Test
    void followUpWeightUpdateAlsoGuardsTheHibernateOwnedTable() throws Exception {
        String sql = read("db/flyway/V20261028_07__logistics_demo_v02_weight.sql");
        assertThat(sql)
                .contains("IF to_regclass('public.vehicles') IS NOT NULL THEN")
                .contains("UPDATE vehicles");
    }

    private String read(String resource) throws Exception {
        try (var input = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            assertThat(input).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
