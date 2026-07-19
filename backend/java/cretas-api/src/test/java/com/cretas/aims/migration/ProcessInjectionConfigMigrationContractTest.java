package com.cretas.aims.migration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessInjectionConfigMigrationContractTest {

    @Test
    void failsClosedThenRenamesTheEmptyTableAndDropsOnlyTheProcessRatio() throws Exception {
        String sql = read("db/flyway/V20261028_82__make_process_injection_config_single_purpose.sql");

        assertThat(sql)
                .contains("LOCK TABLE public.bom_process_seasoning IN ACCESS EXCLUSIVE MODE")
                .contains("config_rows <> 0")
                .contains("work_process_id IS NOT NULL")
                .contains("material_type_id IS NULL")
                .contains("bsi.section = 'COOKING'")
                .contains("SET subsequent_pot_ratio = br.subsequent_pot_ratio")
                .contains("RENAME TO bom_process_injection_configs")
                .contains("DROP COLUMN subsequent_pot_ratio")
                .contains("ALTER COLUMN injection_amount_kg SET NOT NULL")
                .contains("DROP COLUMN cooking_pot_base_kg")
                .contains("DROP COLUMN injection_rate")
                .contains("RENAME TO uq_bpic_recipe_wp")
                .doesNotContainIgnoringCase("DROP TABLE")
                .doesNotContainIgnoringCase("DELETE FROM");

        assertThat(sql.indexOf("config_rows <> 0"))
                .isLessThan(sql.indexOf("SET subsequent_pot_ratio = br.subsequent_pot_ratio"));
        assertThat(sql.indexOf("SET subsequent_pot_ratio = br.subsequent_pot_ratio"))
                .isLessThan(sql.indexOf("RENAME TO bom_process_injection_configs"));
        assertThat(sql.indexOf("RENAME TO bom_process_injection_configs"))
                .isLessThan(sql.indexOf("DROP COLUMN subsequent_pot_ratio"));
    }

    private String read(String resource) throws Exception {
        try (var input = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            assertThat(input).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
