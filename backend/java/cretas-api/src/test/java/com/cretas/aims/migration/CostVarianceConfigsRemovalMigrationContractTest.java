package com.cretas.aims.migration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CostVarianceConfigsRemovalMigrationContractTest {

    @Test
    void failsClosedBeforeDroppingTheRedundantTable() throws Exception {
        String sql = read("db/flyway/V20261028_78__drop_redundant_cost_variance_configs.sql");

        assertThat(sql)
                .contains("LOCK TABLE public.cost_variance_configs IN ACCESS EXCLUSIVE MODE")
                .contains("IF EXISTS (SELECT 1 FROM public.cost_variance_configs)")
                .contains("RAISE EXCEPTION")
                .contains("DROP TABLE public.cost_variance_configs")
                .doesNotContainIgnoringCase("DROP TABLE public.cost_variance_configs CASCADE")
                .doesNotContainIgnoringCase("DELETE FROM cost_variance_configs");

        assertThat(sql.indexOf("LOCK TABLE public.cost_variance_configs"))
                .isLessThan(sql.indexOf("IF EXISTS (SELECT 1 FROM public.cost_variance_configs)"));
        assertThat(sql.indexOf("IF EXISTS (SELECT 1 FROM public.cost_variance_configs)"))
                .isLessThan(sql.indexOf("DROP TABLE public.cost_variance_configs"));
    }

    private String read(String resource) throws Exception {
        try (var input = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            assertThat(input).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
