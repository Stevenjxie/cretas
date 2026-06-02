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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link RestaurantReviewScoreTagsTool} (dim=service|env routing). */
@ExtendWith(MockitoExtension.class)
class RestaurantReviewScoreTagsToolTest {

    private static final String FACTORY_ID = "RES_3101_009";

    @Mock
    private GoldFinanceClient goldClient;

    private RestaurantReviewScoreTagsTool tool;

    @BeforeEach
    void setUp() throws Exception {
        tool = new RestaurantReviewScoreTagsTool();
        Field f = GoldBackedRestaurantTool.class.getDeclaredField("gold");
        f.setAccessible(true);
        f.set(tool, goldClient);
    }

    @Test
    @DisplayName("UT-ST-01: metadata")
    void metadata() {
        assertThat(tool.getToolName()).isEqualTo("restaurant_review_score_tags");
        assertThat(tool.getDescription()).contains("服务").contains("环境");
    }

    @Test
    @DisplayName("UT-ST-02: queryGold — userInput 含'环境' → dim=env")
    void queryGoldEnv() throws Exception {
        when(goldClient.fetchReviewScoreTags(eq(FACTORY_ID), eq("env"), anyInt()))
                .thenReturn(scoreResult("env"));
        Map<String, Object> params = new HashMap<>();
        params.put("userInput", "环境标签都有哪些");

        tool.queryGold(FACTORY_ID, null, null, params);

        verify(goldClient).fetchReviewScoreTags(eq(FACTORY_ID), eq("env"), anyInt());
    }

    @Test
    @DisplayName("UT-ST-03: queryGold — userInput 不含'环境' → dim=service (default)")
    void queryGoldService() throws Exception {
        when(goldClient.fetchReviewScoreTags(eq(FACTORY_ID), eq("service"), anyInt()))
                .thenReturn(scoreResult("service"));
        Map<String, Object> params = new HashMap<>();
        params.put("userInput", "服务标签都有哪些");

        tool.queryGold(FACTORY_ID, null, null, params);

        verify(goldClient).fetchReviewScoreTags(eq(FACTORY_ID), eq("service"), anyInt());
    }

    @Test
    @DisplayName("UT-ST-04: queryGold — explicit dim param env wins")
    void queryGoldExplicitDim() throws Exception {
        when(goldClient.fetchReviewScoreTags(eq(FACTORY_ID), eq("env"), anyInt()))
                .thenReturn(scoreResult("env"));
        Map<String, Object> params = new HashMap<>();
        params.put("dim", "env");

        tool.queryGold(FACTORY_ID, null, null, params);

        verify(goldClient).fetchReviewScoreTags(eq(FACTORY_ID), eq("env"), anyInt());
    }

    @Test
    @DisplayName("UT-ST-05: format() — service dim labels + chart + depth")
    void formatService() {
        Map<String, Object> r = tool.format(scoreResult("service"));
        assertThat(r).containsEntry("dataAvailable", true);
        assertThat(r).containsEntry("维度", "服务");
        assertThat(r.get("message").toString()).contains("服务评价标签");
        assertThat(r).containsKey("chartConfig");
        assertThat(r).containsKey("suggestedFollowups");
        @SuppressWarnings("unchecked")
        Map<String, String> glossary = (Map<String, String>) r.get("glossary");
        assertThat(glossary).containsKey("服务标签");
    }

    @Test
    @DisplayName("UT-ST-06: format() — env dim labels")
    void formatEnv() {
        Map<String, Object> r = tool.format(scoreResult("env"));
        assertThat(r).containsEntry("维度", "环境");
        assertThat(r.get("message").toString()).contains("环境评价标签");
    }

    @Test
    @DisplayName("UT-ST-07: isEmpty / emptyMessage")
    void emptyPaths() {
        Map<String, Object> empty = new HashMap<>();
        empty.put("tags", List.of());
        assertThat(tool.isEmpty(empty)).isTrue();
        assertThat(tool.emptyMessage()).contains("标签");
    }

    private static Map<String, Object> scoreResult(String dim) {
        Map<String, Object> m = new HashMap<>();
        m.put("dim", dim);
        m.put("tags", List.of(tag("服务热情", 1200, 4.9), tag("上菜快", 800, 4.8)));
        return m;
    }

    private static Map<String, Object> tag(String name, int count, double avg) {
        Map<String, Object> m = new HashMap<>();
        m.put("tag", name);
        m.put("count", count);
        m.put("avg_score", avg);
        return m;
    }
}
