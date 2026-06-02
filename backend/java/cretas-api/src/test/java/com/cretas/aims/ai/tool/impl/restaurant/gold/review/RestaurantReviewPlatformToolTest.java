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

/** Unit tests for {@link RestaurantReviewPlatformTool}. */
@ExtendWith(MockitoExtension.class)
class RestaurantReviewPlatformToolTest {

    @Mock
    private GoldFinanceClient goldClient;

    private RestaurantReviewPlatformTool tool;

    @BeforeEach
    void setUp() throws Exception {
        tool = new RestaurantReviewPlatformTool();
        Field f = GoldBackedRestaurantTool.class.getDeclaredField("gold");
        f.setAccessible(true);
        f.set(tool, goldClient);
    }

    @Test
    @DisplayName("UT-PL-01: metadata")
    void metadata() {
        assertThat(tool.getToolName()).isEqualTo("restaurant_review_platform");
        assertThat(tool.getDescription()).contains("平台");
    }

    @Test
    @DisplayName("UT-PL-02: format() — GROUNDTRUTH 点评/美团 + pie chart + depth")
    void formatGroundtruth() {
        Map<String, Object> g = new HashMap<>();
        g.put("platforms", List.of(
                platform("点评", 19189, 4.80),
                platform("美团", 656, 4.57)));

        Map<String, Object> r = tool.format(g);

        assertThat(r).containsEntry("dataAvailable", true);
        String msg = r.get("message").toString();
        assertThat(msg).contains("点评").contains("19189").contains("美团").contains("656");
        @SuppressWarnings("unchecked")
        Map<String, Object> cfg = (Map<String, Object>) r.get("chartConfig");
        assertThat(cfg).containsEntry("type", "pie");
        assertThat(cfg.get("option")).isNotNull();
        assertThat(r).containsKey("suggestedFollowups");
        assertThat(r).containsKey("glossary");
        assertThat(r).containsKey("chartGuide");
    }

    @Test
    @DisplayName("UT-PL-03: isEmpty / emptyMessage")
    void emptyPaths() {
        Map<String, Object> empty = new HashMap<>();
        empty.put("platforms", List.of());
        assertThat(tool.isEmpty(empty)).isTrue();
        assertThat(tool.emptyMessage()).contains("平台");
    }

    private static Map<String, Object> platform(String name, int n, double avgStar) {
        Map<String, Object> m = new HashMap<>();
        m.put("platform", name);
        m.put("review_count", n);
        m.put("avg_star", avgStar);
        m.put("avg_service", 4.8);
        m.put("avg_env", 4.8);
        return m;
    }
}
