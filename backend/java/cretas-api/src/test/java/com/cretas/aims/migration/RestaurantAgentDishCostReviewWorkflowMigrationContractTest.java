package com.cretas.aims.migration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class RestaurantAgentDishCostReviewWorkflowMigrationContractTest {

    private static final String MIGRATION =
            "db/flyway/V20261028_86__restaurant_agent_dish_cost_review_workflow.sql";

    @Test
    void migrationSeedsOnlyPublishedHumanReviewWorkflowWithoutBusinessWriter() throws Exception {
        String sql = read(MIGRATION);
        String normalized = sql.toLowerCase(Locale.ROOT);

        assertThat(sql)
                .contains("RESTAURANT_AGENT_ACTION_REVIEW")
                .contains("restaurant.dish-cost-data-review.v1")
                .contains("'published'")
                .contains("TRUE")
                .contains("\"type\":\"approval\"")
                .contains("\"approverRoles\"")
                .contains("restaurant_owner", "restaurant_manager", "finance_manager")
                .contains("RESTAURANT", "BRANCH");
        assertThat(normalized).containsOnlyOnce("insert into approval_workflows");
        assertThat(normalized)
                .doesNotContain("insert into recipes")
                .doesNotContain("insert into recipe_versions")
                .doesNotContain("insert into bom_recipes")
                .doesNotContain("insert into price_lists")
                .doesNotContain("insert into price_list_items")
                .doesNotContain("update recipes")
                .doesNotContain("update bom_recipes")
                .doesNotContain("update price_lists")
                .doesNotContain("delete from");
    }

    @Test
    void flywayVersionIsUniqueAcrossTheFullDirectory() throws Exception {
        Path flyway = Path.of("src/main/resources/db/flyway");
        try (var files = Files.list(flyway)) {
            assertThat(files
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("V20261028_86__")))
                    .containsExactly("V20261028_86__restaurant_agent_dish_cost_review_workflow.sql");
        }
    }

    private String read(String resource) throws Exception {
        try (var input = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            assertThat(input).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
