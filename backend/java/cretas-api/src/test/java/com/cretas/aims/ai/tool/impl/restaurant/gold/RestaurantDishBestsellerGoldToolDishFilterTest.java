package com.cretas.aims.ai.tool.impl.restaurant.gold;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RestaurantDishBestsellerGoldTool dish-scoped formatting")
class RestaurantDishBestsellerGoldToolDishFilterTest {

    private final RestaurantDishBestsellerGoldTool tool = new RestaurantDishBestsellerGoldTool();

    @Test
    @DisplayName("format keeps dish_id and exposes top_dish")
    void formatKeepsDishIdAndTopDish() {
        Map<String, Object> result = tool.format(goldResult());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rank = (List<Map<String, Object>>) result.get("畅销TOP5");
        assertThat(rank).hasSize(2);
        assertThat(rank.get(0).get("dish_id")).isEqualTo(201);
        assertThat(rank.get(0).get("菜品")).isEqualTo("叮咚卤猪蹄");

        @SuppressWarnings("unchecked")
        Map<String, Object> topDish = (Map<String, Object>) result.get("top_dish");
        assertThat(topDish).isEqualTo(rank.get(0));
    }

    @Test
    @DisplayName("format excludes low-value support items before ranking")
    void formatExcludesLowValueSupportItemsBeforeRanking() {
        Map<String, Object> result = goldResult();
        result.put("top_products", List.of(
                Map.of("product_id", 300, "product_name", "米饭",
                        "qty_sold", 9000.0, "revenue", 90000.0),
                Map.of("product_id", 301, "product_name", "王老吉",
                        "qty_sold", 5000.0, "revenue", 50000.0),
                Map.of("product_id", 201, "product_name", "招牌青花椒鱼",
                        "qty_sold", 1500.0, "revenue", 45000.0),
                Map.of("product_id", 202, "product_name", "抖音松叶蟹368套餐",
                        "qty_sold", 800.0, "revenue", 28000.0)
        ));

        Map<String, Object> formattedResult = tool.format(result);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rank = (List<Map<String, Object>>) formattedResult.get("畅销TOP5");
        assertThat(rank).extracting(row -> row.get("菜品"))
                .containsExactly("招牌青花椒鱼", "抖音松叶蟹368套餐");
        assertThat(formattedResult.get("message").toString()).contains("已先排除低价值配套项");
        assertThat(formattedResult.get("message").toString()).doesNotContain("1. 米饭");
    }

    @Test
    @DisplayName("dish_id filter returns only the selected dish row")
    void dishIdFilterReturnsSingleDish() {
        Map<String, Object> input = goldResult();
        input.put("dish_id", "202");

        Map<String, Object> result = tool.format(input);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rank = (List<Map<String, Object>>) result.get("畅销TOP5");
        assertThat(rank).hasSize(1);
        assertThat(rank.get(0).get("dish_id")).isEqualTo(202);
        assertThat(rank.get(0).get("菜品")).isEqualTo("花椒辣子鸡");
    }

    @Test
    @DisplayName("dish_name filter returns only the selected dish row")
    void dishNameFilterReturnsSingleDish() {
        Map<String, Object> input = goldResult();
        input.put("dish_name", "花椒辣子鸡");

        Map<String, Object> result = tool.format(input);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rank = (List<Map<String, Object>>) result.get("畅销TOP5");
        assertThat(rank).singleElement().satisfies(row -> {
            assertThat(row.get("dish_id")).isEqualTo(202);
            assertThat(row.get("菜品")).isEqualTo("花椒辣子鸡");
        });
    }

    @Test
    @DisplayName("missing selected dish returns actionable empty result")
    void missingSelectedDishReturnsEmptyResult() {
        Map<String, Object> input = goldResult();
        input.put("dish_id", "999");

        Map<String, Object> result = tool.format(input);

        assertThat(result.get("dataAvailable")).isEqualTo(false);
        assertThat(result.get("message")).isEqualTo(tool.emptyMessage());
        assertThat(result.get("actionHint")).isNotNull();
    }

    @Test
    @DisplayName("parameter schema accepts optional dish filters")
    void schemaContainsDishFilters() {
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) tool.getParametersSchema().get("properties");

        assertThat(properties).containsKeys("month", "dish_name", "dish_id");
    }

    private static Map<String, Object> goldResult() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("start_month", "2026-03");
        result.put("end_month", "2026-03");
        result.put("top_products", List.of(
                Map.of("product_id", 201, "product_name", "叮咚卤猪蹄",
                        "qty_sold", 1500.0, "revenue", 45000.0),
                Map.of("product_id", 202, "product_name", "花椒辣子鸡",
                        "qty_sold", 800.0, "revenue", 28000.0)
        ));
        return result;
    }
}
