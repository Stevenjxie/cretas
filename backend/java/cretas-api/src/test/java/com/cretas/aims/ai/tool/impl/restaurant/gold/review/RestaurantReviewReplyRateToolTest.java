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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link RestaurantReviewReplyRateTool}. */
@ExtendWith(MockitoExtension.class)
class RestaurantReviewReplyRateToolTest {

    @Mock
    private GoldFinanceClient goldClient;

    private RestaurantReviewReplyRateTool tool;

    @BeforeEach
    void setUp() throws Exception {
        tool = new RestaurantReviewReplyRateTool();
        Field f = GoldBackedRestaurantTool.class.getDeclaredField("gold");
        f.setAccessible(true);
        f.set(tool, goldClient);
    }

    @Test
    @DisplayName("UT-RR-01: metadata")
    void metadata() {
        assertThat(tool.getToolName()).isEqualTo("restaurant_review_reply_rate");
        assertThat(tool.getDescription()).contains("回复率");
    }

    @Test
    @DisplayName("UT-RR-02: format() — GROUNDTRUTH 98% + 未回复差评数 + pie + depth")
    void formatGroundtruth() {
        Map<String, Object> g = new HashMap<>();
        g.put("replied", 19452);
        g.put("not_replied", 393);
        g.put("not_replied_low_star", 12);
        g.put("total_with_status", 19845);
        g.put("reply_rate", 98.0);

        Map<String, Object> r = tool.format(g);

        assertThat(r).containsEntry("dataAvailable", true);
        assertThat(r).containsEntry("已回复数", 19452);
        assertThat(r).containsEntry("未回复差评数", 12);
        String msg = r.get("message").toString();
        assertThat(msg).contains("98.00").contains("19452").contains("393");
        assertThat(msg).contains("12").contains("差评");
        @SuppressWarnings("unchecked")
        Map<String, Object> cfg = (Map<String, Object>) r.get("chartConfig");
        assertThat(cfg).containsEntry("type", "pie");
        assertThat(r).containsKey("suggestedFollowups");
        assertThat(r).containsKey("glossary");
    }

    @Test
    @DisplayName("UT-RR-03: format() — all bad reviews replied → maintained message")
    void formatAllReplied() {
        Map<String, Object> g = new HashMap<>();
        g.put("replied", 100);
        g.put("not_replied", 0);
        g.put("not_replied_low_star", 0);
        g.put("total_with_status", 100);
        g.put("reply_rate", 100.0);

        Map<String, Object> r = tool.format(g);
        assertThat(r.get("message").toString()).contains("差评均已回复");
    }

    @Test
    @DisplayName("UT-RR-04: isEmpty / emptyMessage")
    void emptyPaths() {
        Map<String, Object> empty = new HashMap<>();
        empty.put("total_with_status", 0);
        assertThat(tool.isEmpty(empty)).isTrue();
        assertThat(tool.emptyMessage()).contains("回复状态");
    }
}
