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

/** Unit tests for {@link RestaurantReviewVipTagsTool}. */
@ExtendWith(MockitoExtension.class)
class RestaurantReviewVipTagsToolTest {

    @Mock
    private GoldFinanceClient goldClient;

    private RestaurantReviewVipTagsTool tool;

    @BeforeEach
    void setUp() throws Exception {
        tool = new RestaurantReviewVipTagsTool();
        Field f = GoldBackedRestaurantTool.class.getDeclaredField("gold");
        f.setAccessible(true);
        f.set(tool, goldClient);
    }

    @Test
    @DisplayName("UT-VT-01: metadata")
    void metadata() {
        assertThat(tool.getToolName()).isEqualTo("restaurant_review_vip_tags");
        assertThat(tool.getDescription()).contains("VIP").contains("非具体菜名");
    }

    @Test
    @DisplayName("UT-VT-02: format() — four buckets joined + chart + 非菜名 honesty + depth")
    void formatFourBuckets() {
        Map<String, Object> g = new HashMap<>();
        g.put("vip_good_tags", List.of(tag("味道好", 200), tag("环境好", 80)));
        g.put("vip_bad_tags", List.of(tag("太贵", 5)));
        g.put("normal_good_tags", List.of(tag("实惠", 1500)));
        g.put("normal_bad_tags", List.of(tag("份量小", 30)));

        Map<String, Object> r = tool.format(g);

        assertThat(r).containsEntry("dataAvailable", true);
        String msg = r.get("message").toString();
        assertThat(msg).contains("VIP 好评高频").contains("味道好(200)");
        assertThat(msg).contains("非VIP 好评高频").contains("实惠(1500)");
        assertThat(msg).contains("非具体菜名");
        assertThat(r).containsKey("chartConfig");
        assertThat(r).containsKey("suggestedFollowups");
        assertThat(r).containsKey("glossary");
        assertThat(r).containsKey("chartGuide");
    }

    @Test
    @DisplayName("UT-VT-03: format() — empty bucket renders 暂无")
    void formatEmptyBucket() {
        Map<String, Object> g = new HashMap<>();
        g.put("vip_good_tags", List.of(tag("味道好", 10)));
        g.put("vip_bad_tags", List.of());
        g.put("normal_good_tags", List.of(tag("实惠", 5)));
        g.put("normal_bad_tags", List.of());

        Map<String, Object> r = tool.format(g);
        assertThat(r.get("message").toString()).contains("（暂无）");
    }

    @Test
    @DisplayName("UT-VT-04: isEmpty / emptyMessage")
    void emptyPaths() {
        Map<String, Object> empty = new HashMap<>();
        empty.put("vip_good_tags", List.of());
        empty.put("normal_good_tags", List.of());
        assertThat(tool.isEmpty(empty)).isTrue();
        assertThat(tool.emptyMessage()).contains("菜品标签");
    }

    private static Map<String, Object> tag(String name, int count) {
        Map<String, Object> m = new HashMap<>();
        m.put("tag", name);
        m.put("count", count);
        return m;
    }
}
