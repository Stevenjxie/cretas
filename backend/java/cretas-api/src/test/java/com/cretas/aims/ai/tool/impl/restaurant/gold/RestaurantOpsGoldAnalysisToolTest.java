package com.cretas.aims.ai.tool.impl.restaurant.gold;

import com.cretas.aims.client.GoldFinanceClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RestaurantOpsGoldAnalysisToolTest {

    @Test
    @DisplayName("restaurant ops report response carries owner decision bridge followups")
    void opsReportCarriesOwnerDecisionBridgeFollowups() throws Exception {
        GoldFinanceClient gold = mock(GoldFinanceClient.class);
        when(gold.fetchRestaurantOpsAnalysis(
                eq("DEMO_REST"), any(), any(), eq("RESTAURANT_OPS_WASTAGE_TOP")))
                .thenReturn(Map.of(
                        "success", true,
                        "answer", "损耗金额排名已经算好，活鱼和底料是主要风险。",
                        "charts", Collections.emptyList(),
                        "insights", Collections.emptyList()
                ));

        RestaurantOpsGoldAnalysisTool tool = new RestaurantOpsGoldAnalysisTool(gold);
        Map<String, Object> result = tool.doExecute(
                "DEMO_REST",
                Map.of(
                        "userInput", "损耗金额排名和原因占比",
                        "intentCode", "RESTAURANT_OPS_WASTAGE_TOP"
                ),
                Collections.emptyMap());

        assertThat(result).containsEntry("source", "restaurant_ops_gold");
        assertThat(result).containsKey("decisionBridge");
        assertThat(result).containsKey("suggestedFollowups");

        @SuppressWarnings("unchecked")
        Map<String, Object> bridge = (Map<String, Object>) result.get("decisionBridge");
        assertThat(bridge).containsEntry("answerMode", "report_with_owner_action");
        assertThat(bridge).containsEntry("ownerActionScenario", "cost_margin");
        assertThat(bridge.get("plainDecision").toString())
                .contains("沿用本会话最近的经营主题和时间范围")
                .contains("新话题");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> followups = (List<Map<String, Object>>) result.get("suggestedFollowups");
        assertThat(followups).isNotEmpty();
        assertThat(followups.get(0)).containsEntry("ownerActionScenario", "cost_margin");
        verify(gold).fetchRestaurantOpsAnalysis(
                eq("DEMO_REST"), any(), any(), eq("RESTAURANT_OPS_WASTAGE_TOP"));
    }

    @Test
    @DisplayName("blank restaurant answer is never reported as completed")
    void blankRestaurantAnswerIsNotReportedAsCompleted() throws Exception {
        GoldFinanceClient gold = mock(GoldFinanceClient.class);
        when(gold.fetchRestaurantOpsAnalysis(
                eq("DEMO_REST"), any(), any(), eq("RESTAURANT_OPS_SALES_SUMMARY")))
                .thenReturn(Map.of("success", true, "answer", "   "));

        RestaurantOpsGoldAnalysisTool tool = new RestaurantOpsGoldAnalysisTool(gold);
        Map<String, Object> result = tool.doExecute(
                "DEMO_REST",
                Map.of(
                        "userInput", "昨天营业额比前天高还是低",
                        "intentCode", "RESTAURANT_OPS_SALES_SUMMARY"
                ),
                Collections.emptyMap());

        assertThat(result).containsEntry("dataAvailable", false);
        assertThat(result.get("answer").toString())
                .contains("没有获得可展示的经营结果")
                .doesNotContain("已完成");
    }
}
