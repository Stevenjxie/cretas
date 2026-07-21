package com.cretas.aims.ai.tool.impl.restaurant.gold;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RestaurantStoreRevenueRankGoldTool store-scoped formatting")
class RestaurantStoreRevenueRankGoldToolStoreFilterTest {

    private final RestaurantStoreRevenueRankGoldTool tool = new RestaurantStoreRevenueRankGoldTool();

    @Test
    @DisplayName("format keeps store_id and exposes top_store")
    void formatKeepsStoreIdAndTopStore() {
        Map<String, Object> result = tool.format(goldResult());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rank = (List<Map<String, Object>>) result.get("门店营收排行");
        assertThat(rank).hasSize(2);
        assertThat(rank.get(0).get("store_id")).isEqualTo(101);
        assertThat(rank.get(0).get("门店")).isEqualTo("人民广场店");
        assertThat(rank.get(0).get("客单价")).isEqualTo(new BigDecimal("100.00"));

        @SuppressWarnings("unchecked")
        Map<String, Object> topStore = (Map<String, Object>) result.get("top_store");
        assertThat(topStore).isEqualTo(rank.get(0));
    }

    @Test
    @DisplayName("direct best-store question returns only the winner and evidence")
    void directBestStoreQuestionIsConcise() {
        Map<String, Object> input = goldResult();
        input.put("userInput", "哪家店业绩最好？先直接告诉我第一名和核心依据。");

        Map<String, Object> result = tool.format(input);

        assertThat(result.get("message").toString())
                .contains("第一名是人民广场店")
                .contains("核心依据")
                .contains("2 家门店")
                .contains("营收 2000 最高")
                .doesNotContain("2. 陆家嘴店")
                .doesNotContain("建议：");
        assertThat(result).containsEntry("suppressActionAdvice", true);
    }

    @Test
    @DisplayName("store_id filter returns only the selected store row")
    void storeIdFilterReturnsSingleStore() {
        Map<String, Object> input = goldResult();
        input.put("store_id", "102");

        Map<String, Object> result = tool.format(input);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rank = (List<Map<String, Object>>) result.get("门店营收排行");
        assertThat(rank).hasSize(1);
        assertThat(rank.get(0).get("store_id")).isEqualTo(102);
        assertThat(rank.get(0).get("门店")).isEqualTo("陆家嘴店");
        assertThat(rank.get(0).get("营收")).isEqualTo(1000.0);
        assertThat(rank.get(0).get("单数")).isEqualTo(25);
        assertThat(rank.get(0).get("客单价")).isEqualTo(new BigDecimal("40.00"));
    }

    @Test
    @DisplayName("store_name filter returns only the selected store row")
    void storeNameFilterReturnsSingleStore() {
        Map<String, Object> input = goldResult();
        input.put("store_name", "陆家嘴店");

        Map<String, Object> result = tool.format(input);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rank = (List<Map<String, Object>>) result.get("门店营收排行");
        assertThat(rank).singleElement().satisfies(row -> {
            assertThat(row.get("store_id")).isEqualTo(102);
            assertThat(row.get("门店")).isEqualTo("陆家嘴店");
        });
    }

    @Test
    @DisplayName("missing selected store returns actionable empty result")
    void missingSelectedStoreReturnsEmptyResult() {
        Map<String, Object> input = goldResult();
        input.put("store_id", "999");

        Map<String, Object> result = tool.format(input);

        assertThat(result.get("dataAvailable")).isEqualTo(false);
        assertThat(result.get("message")).isEqualTo(tool.emptyMessage());
        assertThat(result.get("actionHint")).isNotNull();
    }

    @Test
    @DisplayName("parameter schema accepts optional store filters")
    void schemaContainsStoreFilters() {
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) tool.getParametersSchema().get("properties");

        assertThat(properties).containsKeys("month", "store_name", "store_id");
    }

    private static Map<String, Object> goldResult() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("start_date", "2026-03-01");
        result.put("end_date", "2026-03-31");
        result.put("total_revenue", 3000.0);
        result.put("store_count", 2);
        result.put("top_stores", List.of(
                Map.of("store_id", 101, "store_name", "人民广场店", "revenue", 2000.0, "bill_count", 20),
                Map.of("store_id", 102, "store_name", "陆家嘴店", "revenue", 1000.0, "bill_count", 25)
        ));
        return result;
    }
}
