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

/** Unit tests for {@link RestaurantReviewTimePeriodTool}. */
@ExtendWith(MockitoExtension.class)
class RestaurantReviewTimePeriodToolTest {

    @Mock
    private GoldFinanceClient goldClient;

    private RestaurantReviewTimePeriodTool tool;

    @BeforeEach
    void setUp() throws Exception {
        tool = new RestaurantReviewTimePeriodTool();
        Field f = GoldBackedRestaurantTool.class.getDeclaredField("gold");
        f.setAccessible(true);
        f.set(tool, goldClient);
    }

    @Test
    @DisplayName("UT-TP-01: metadata")
    void metadata() {
        assertThat(tool.getToolName()).isEqualTo("restaurant_review_time_period");
        assertThat(tool.getDescription()).contains("时段");
    }

    @Test
    @DisplayName("UT-TP-02: format() — GROUNDTRUTH buckets + coverage note + chart + depth")
    void formatGroundtruth() {
        // total = periods sum (14425) + null (5420) = 19845 → coverage ~73%
        Map<String, Object> g = new HashMap<>();
        g.put("null_period_count", 5420);
        g.put("total_reviews", 19845);
        g.put("periods", List.of(
                period("早(5-10点)", 522, 4.44),
                period("午(11-14点)", 5989, 4.85),
                period("下午(15-16点)", 625, 4.58),
                period("晚(17-21点)", 6810, 4.82),
                period("夜(22-4点)", 479, 4.31)));

        Map<String, Object> r = tool.format(g);

        assertThat(r).containsEntry("dataAvailable", true);
        String msg = r.get("message").toString();
        assertThat(msg).contains("午(11-14点)").contains("4.85");
        assertThat(msg).contains("晚(17-21点)").contains("4.82");
        // coverage note: round(100*(19845-5420)/19845) = 73%
        assertThat(msg).contains("73%").contains("5420");
        assertThat(r).containsKey("chartConfig");
        assertThat(r).containsKey("suggestedFollowups");
        @SuppressWarnings("unchecked")
        Map<String, String> glossary = (Map<String, String>) r.get("glossary");
        assertThat(glossary.get("覆盖率")).contains("73%");
    }

    @Test
    @DisplayName("UT-TP-03: isEmpty / emptyMessage")
    void emptyPaths() {
        Map<String, Object> empty = new HashMap<>();
        empty.put("periods", List.of());
        assertThat(tool.isEmpty(empty)).isTrue();
        assertThat(tool.emptyMessage()).contains("时间");
    }

    private static Map<String, Object> period(String label, int n, double avgStar) {
        Map<String, Object> m = new HashMap<>();
        m.put("period", label);
        m.put("review_count", n);
        m.put("avg_star", avgStar);
        return m;
    }
}
