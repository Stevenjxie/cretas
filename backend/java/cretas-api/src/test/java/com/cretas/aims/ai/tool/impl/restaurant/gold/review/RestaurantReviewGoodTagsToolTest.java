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

/** Unit tests for {@link RestaurantReviewGoodTagsTool}. */
@ExtendWith(MockitoExtension.class)
class RestaurantReviewGoodTagsToolTest {

    private static final String FACTORY_ID = "RES_3101_009";

    @Mock
    private GoldFinanceClient goldClient;

    private RestaurantReviewGoodTagsTool tool;

    @BeforeEach
    void setUp() throws Exception {
        tool = new RestaurantReviewGoodTagsTool();
        Field f = GoldBackedRestaurantTool.class.getDeclaredField("gold");
        f.setAccessible(true);
        f.set(tool, goldClient);
    }

    @Test
    @DisplayName("UT-GT-01: metadata")
    void metadata() {
        assertThat(tool.getToolName()).isEqualTo("restaurant_review_good_tags");
        assertThat(tool.getDescription()).contains("好评").contains("非具体菜名");
        assertThat(tool.getParametersSchema()).containsKey("properties");
    }

    @Test
    @DisplayName("UT-GT-02: format() — GROUNDTRUTH tags + chart + depth fields + 非菜名 honesty")
    void formatGroundtruth() {
        Map<String, Object> g = new HashMap<>();
        g.put("high_star_count", 18139);
        g.put("tags", List.of(
                tag("味道好", 5998),
                tag("实惠", 1791),
                tag("鲜嫩", 1394),
                tag("新鲜", 1295),
                tag("香辣", 1046)));

        Map<String, Object> r = tool.format(g);

        assertThat(r).containsEntry("dataAvailable", true);
        assertThat(r).containsEntry("好评总数", 18139);
        String msg = r.get("message").toString();
        assertThat(msg).contains("味道好").contains("5998").contains("非菜名");
        assertThat(r).containsKey("chartConfig");
        @SuppressWarnings("unchecked")
        Map<String, Object> cfg = (Map<String, Object>) r.get("chartConfig");
        assertThat(cfg.get("option")).isNotNull();
        assertThat(r).containsKey("suggestedFollowups");
        assertThat(r).containsKey("glossary");
        assertThat(r).containsKey("chartGuide");
        @SuppressWarnings("unchecked")
        Map<String, String> glossary = (Map<String, String>) r.get("glossary");
        assertThat(glossary.get("口味/品质标签")).contains("不是具体菜名");
    }

    @Test
    @DisplayName("UT-GT-03: isEmpty / emptyMessage")
    void emptyPaths() {
        Map<String, Object> empty = new HashMap<>();
        empty.put("tags", List.of());
        assertThat(tool.isEmpty(empty)).isTrue();
        assertThat(tool.isEmpty(new HashMap<>())).isTrue();
        assertThat(tool.emptyMessage()).contains("菜品标签");
    }

    private static Map<String, Object> tag(String name, int count) {
        Map<String, Object> m = new HashMap<>();
        m.put("tag", name);
        m.put("count", count);
        return m;
    }
}
