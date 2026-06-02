package com.cretas.aims.ai.tool.impl.restaurant.gold.review;

import com.cretas.aims.ai.tool.impl.restaurant.gold.GoldBackedRestaurantTool;
import com.cretas.aims.client.GoldFinanceClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link RestaurantReviewTrendTool}. */
@ExtendWith(MockitoExtension.class)
class RestaurantReviewTrendToolTest {

    @Mock
    private GoldFinanceClient goldClient;

    private RestaurantReviewTrendTool tool;

    @BeforeEach
    void setUp() throws Exception {
        tool = new RestaurantReviewTrendTool();
        Field f = GoldBackedRestaurantTool.class.getDeclaredField("gold");
        f.setAccessible(true);
        f.set(tool, goldClient);
    }

    @Test
    @DisplayName("UT-TR-01: metadata")
    void metadata() {
        assertThat(tool.getToolName()).isEqualTo("restaurant_review_trend");
        assertThat(tool.getDescription()).contains("趋势");
    }

    @Test
    @DisplayName("UT-TR-02: format() — dual-axis line option + first/last conclusion + null note")
    void formatDualAxis() {
        Map<String, Object> g = new HashMap<>();
        g.put("null_period_count", 5420);
        g.put("months", List.of(
                month("2025-01", 1000, 4.60),
                month("2025-02", 1200, 4.75),
                month("2025-03", 1500, 4.90)));

        Map<String, Object> r = tool.format(g);

        assertThat(r).containsEntry("dataAvailable", true);
        String msg = r.get("message").toString();
        assertThat(msg).contains("4.60").contains("4.90").contains("口碑上升");
        assertThat(msg).contains("5420");
        @SuppressWarnings("unchecked")
        Map<String, Object> cfg = (Map<String, Object>) r.get("chartConfig");
        assertThat(cfg).containsEntry("type", "line");
        @SuppressWarnings("unchecked")
        Map<String, Object> opt = (Map<String, Object>) cfg.get("option");
        // dual y-axis (list of 2)
        assertThat(opt.get("yAxis")).isInstanceOf(List.class);
        assertThat((List<?>) opt.get("yAxis")).hasSize(2);
        // two series (line + bar)
        assertThat((List<?>) opt.get("series")).hasSize(2);
        assertThat(r).containsKey("suggestedFollowups");
        assertThat(r).containsKey("glossary");
    }

    @Test
    @DisplayName("UT-TR-03: isEmpty / emptyMessage")
    void emptyPaths() {
        Map<String, Object> empty = new HashMap<>();
        empty.put("months", List.of());
        assertThat(tool.isEmpty(empty)).isTrue();
        assertThat(tool.emptyMessage()).contains("时间");
    }

    private static Map<String, Object> month(String m, int n, double avgStar) {
        Map<String, Object> row = new HashMap<>();
        row.put("month", m);
        row.put("review_count", n);
        row.put("avg_star", avgStar);
        return row;
    }
}
