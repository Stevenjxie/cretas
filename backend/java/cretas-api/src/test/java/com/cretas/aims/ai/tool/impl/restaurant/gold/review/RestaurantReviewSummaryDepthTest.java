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

/**
 * Task 7 backfill smoke test: the EXISTING review summary tool now carries
 * suggestedFollowups / glossary / chartGuide after attachDepth() backfill.
 */
@ExtendWith(MockitoExtension.class)
class RestaurantReviewSummaryDepthTest {

    @Mock
    private GoldFinanceClient goldClient;

    private RestaurantReviewSummaryTool tool;

    @BeforeEach
    void setUp() throws Exception {
        tool = new RestaurantReviewSummaryTool();
        Field f = GoldBackedRestaurantTool.class.getDeclaredField("gold");
        f.setAccessible(true);
        f.set(tool, goldClient);
    }

    @Test
    @DisplayName("UT-SUMD-01: format() carries depth fields after backfill")
    void summaryCarriesDepth() {
        Map<String, Object> g = new HashMap<>();
        g.put("total_reviews", 19845);
        g.put("avg_star", 4.79);
        g.put("avg_service", 4.80);
        g.put("avg_env", 4.79);
        g.put("avg_taste", 4.79);
        g.put("low_star_count", 396);
        g.put("high_star_count", 18139);
        g.put("vip_count", 2485);
        g.put("store_count", 28);
        g.put("city_count", 2);
        g.put("vip_good_tags", List.of(tag("味道好", 200), tag("环境好", 80)));
        g.put("vip_bad_tags", List.of(tag("太贵", 5)));
        g.put("normal_good_tags", List.of(tag("实惠", 1500)));
        g.put("normal_bad_tags", List.of(tag("份量小", 30)));
        g.put("dimension_scores", List.of(
                dim("星级", 4.79), dim("服务", 4.80), dim("环境", 4.79), dim("口味", 4.79)));

        Map<String, Object> r = tool.format(g);

        assertThat(r).containsKey("suggestedFollowups");
        assertThat(r).containsKey("glossary");
        assertThat(r).containsKey("chartGuide");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> followups =
                (List<Map<String, Object>>) r.get("suggestedFollowups");
        assertThat(followups).isNotEmpty();
        assertThat(followups.get(0)).containsKeys("label", "question");
        @SuppressWarnings("unchecked")
        Map<String, String> glossary = (Map<String, String>) r.get("glossary");
        assertThat(glossary).containsKeys("星级分", "服务分", "差评");
        assertThat(r.get("message").toString())
                .contains("高频好评词")
                .contains("VIP 味道好(200)")
                .contains("非VIP 实惠(1500)")
                .contains("高频差评词")
                .contains("份量小(30)");
    }

    private static Map<String, Object> dim(String name, double value) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("value", value);
        return m;
    }

    private static Map<String, Object> tag(String name, int count) {
        Map<String, Object> m = new HashMap<>();
        m.put("tag", name);
        m.put("count", count);
        return m;
    }
}
