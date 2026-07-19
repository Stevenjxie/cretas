package com.cretas.aims.migration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyProductRecipesRemovalMigrationContractTest {

    @Test
    void locksAndGuardsTheAuthorizedTestSnapshotBeforeDroppingBothTables() throws Exception {
        String sql = read("db/flyway/V20261028_79__drop_legacy_product_recipes.sql");

        assertThat(sql)
                .contains("LOCK TABLE public.recipe_ingredients IN ACCESS EXCLUSIVE MODE")
                .contains("LOCK TABLE public.product_recipes IN ACCESS EXCLUSIVE MODE")
                .contains("recipe_rows > 2")
                .contains("ingredient_rows > 17")
                .contains("factory_id IS DISTINCT FROM 'DEMO_FACTORY'")
                .contains("product_type_id IS DISTINCT FROM 'DF_pt10'")
                .contains("460add70-680f-4257-8f02-2d595e18c92b")
                .contains("53d9c92c-9c35-4989-bbb7-e400e1a4a5ca")
                .contains("RAISE EXCEPTION")
                .contains("DROP TABLE public.recipe_ingredients")
                .contains("DROP TABLE public.product_recipes")
                .doesNotContainIgnoringCase(" CASCADE")
                .doesNotContainIgnoringCase("DELETE FROM");

        assertThat(sql.indexOf("LOCK TABLE public.recipe_ingredients"))
                .isLessThan(sql.indexOf("RAISE EXCEPTION"));
        assertThat(sql.indexOf("RAISE EXCEPTION"))
                .isLessThan(sql.indexOf("DROP TABLE public.recipe_ingredients"));
        assertThat(sql.indexOf("DROP TABLE public.recipe_ingredients"))
                .isLessThan(sql.indexOf("DROP TABLE public.product_recipes"));
    }

    private String read(String resource) throws Exception {
        try (var input = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            assertThat(input).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
