package com.cretas.aims.ai.tool.impl.restaurant.gold;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.client.GoldFinanceClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
                eq("DEMO_REST"), any(), any(), eq("RESTAURANT_OPS_WASTAGE_TOP"), any()))
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
                eq("DEMO_REST"), any(), any(), eq("RESTAURANT_OPS_WASTAGE_TOP"), any());
    }

    @Test
    @DisplayName("blank restaurant answer is never reported as completed")
    void blankRestaurantAnswerIsNotReportedAsCompleted() throws Exception {
        GoldFinanceClient gold = mock(GoldFinanceClient.class);
        when(gold.fetchRestaurantOpsAnalysis(
                eq("DEMO_REST"), any(), any(), eq("RESTAURANT_OPS_SALES_SUMMARY"), any()))
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

    @Test
    @DisplayName("today revenue and margin outage is reported without substituting another date")
    void todayRevenueMarginOutageIsTruthful() throws Exception {
        String question = "今天营收、毛利和毛利率是多少，缺数据不要用其他日期代替";
        Map<String, Object> result = executeWithTransportFailure(question, "RESTAURANT_OPS_SALES_SUMMARY");

        assertThat(result).containsEntry("dataAvailable", false);
        assertThat(result.get("answer").toString())
                .contains("今天（" + LocalDate.now(ZoneId.of("Asia/Shanghai")) + "）")
                .contains("营收", "毛利", "毛利率", "无法可靠读取", "不会拿其他日期的数据替代")
                .doesNotContain("Python", "Gold", "restaurant_ops", "IOException");
    }

    @Test
    @DisplayName("yesterday comparison outage uses dynamic Shanghai dates")
    void yesterdayComparisonOutageUsesDynamicDates() throws Exception {
        String question = "昨天营业额比前天高还是低？";
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        Map<String, Object> result = executeWithTransportFailure(question, "RESTAURANT_OPS_SALES_SUMMARY");

        assertThat(result).containsEntry("dataAvailable", false);
        assertThat(result.get("answer").toString())
                .contains("昨天（" + today.minusDays(1) + "）")
                .contains("前天（" + today.minusDays(2) + "）")
                .contains("不能判断哪天更高", "不会用其他日期替代")
                .doesNotContain("Python", "Gold", "restaurant_ops", "IOException");
    }

    @Test
    @DisplayName("service speed outage names available and missing dimensions")
    void serviceSpeedOutageExplainsMissingDimensions() throws Exception {
        String question = "服务速度和出餐慢的根因是什么？";
        Map<String, Object> result = executeWithTransportFailure(question, "RESTAURANT_OPS_SERVICE_SPEED");

        assertThat(result).containsEntry("dataAvailable", false);
        assertThat(result.get("answer").toString())
                .contains("营业额", "订单量", "客单价", "成本", "毛利")
                .contains("缺少", "点单", "备餐", "出餐", "上菜", "员工排班", "顾客反馈")
                .contains("不会用营业额代替")
                .doesNotContain("Python", "Gold", "restaurant_ops", "IOException");
    }

    @Test
    @DisplayName("dish optimization outage names available and missing dimensions without substitution")
    void dishOptimizationOutageIsTruthful() throws Exception {
        String question = "分析哪些菜品可以从菜单中被优化。优化不只指慢销，请综合销量、销售额、毛利、退菜、差评、制作时长和损耗；缺少哪些数据也要逐项说明。";
        Map<String, Object> result = executeWithTransportFailure(question, "RESTAURANT_OPS_GROSS_MARGIN");

        assertThat(result).containsEntry("dataAvailable", false);
        assertThat(result.get("answer").toString())
                .contains("不能给出", "菜品名单")
                .contains("销量", "销售额", "毛利", "食材损耗")
                .contains("退菜", "评分", "评价", "制作过程起止时间")
                .contains("不会用", "不会把部分维度包装成完整")
                .doesNotContain("Python", "Gold", "restaurant_ops", "IOException");
    }

    @Test
    @DisplayName("store margin followup outage preserves store coreference")
    void storeMarginFollowupOutagePreservesCoreference() throws Exception {
        String question = "该店的毛利率呢？";
        Map<String, Object> result = executeWithTransportFailure(question, "RESTAURANT_OPS_STORE_MARGIN");

        assertThat(result).containsEntry("dataAvailable", false);
        assertThat(result.get("answer").toString())
                .contains("已识别", "该店", "毛利率", "成本覆盖", "毛利排名")
                .contains("不会用营业额替代毛利率")
                .doesNotContain("Python", "Gold", "restaurant_ops", "IOException");
    }

    @Test
    @DisplayName("data unavailable answer is never wrapped as a successful query")
    void unavailableAnswerUsesFailedOuterContract() throws Exception {
        GoldFinanceClient gold = mock(GoldFinanceClient.class);
        when(gold.fetchRestaurantOpsAnalysis(
                eq("DEMO_REST"), any(), any(), eq("RESTAURANT_OPS_SALES_SUMMARY"), any()))
                .thenThrow(new IOException("downstream unavailable"));
        RestaurantOpsGoldAnalysisTool tool = new RestaurantOpsGoldAnalysisTool(gold);
        ObjectMapper objectMapper = new ObjectMapper();
        ReflectionTestUtils.setField(tool, "objectMapper", objectMapper);
        ToolCall call = ToolCall.of(
                "outage-contract",
                tool.getToolName(),
                "{\"userInput\":\"今天营收、毛利和毛利率是多少\","
                        + "\"intentCode\":\"RESTAURANT_OPS_SALES_SUMMARY\"}");

        String responseJson = tool.execute(
                call,
                Map.of("factoryId", "DEMO_REST", "userId", 1L));
        @SuppressWarnings("unchecked")
        Map<String, Object> envelope = objectMapper.readValue(responseJson, Map.class);

        assertThat(envelope).containsEntry("success", false);
        assertThat(envelope.get("message").toString())
                .contains("今天", "营收", "毛利", "毛利率", "无法可靠读取")
                .doesNotContain("Python", "Gold", "restaurant_ops", "IOException");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) envelope.get("data");
        assertThat(data).containsEntry("dataAvailable", false);
    }

    @Test
    @DisplayName("unknown transport failure is not converted into a successful-looking answer")
    void unknownTransportFailureStillThrows() throws Exception {
        GoldFinanceClient gold = mock(GoldFinanceClient.class);
        when(gold.fetchRestaurantOpsAnalysis(
                eq("DEMO_REST"), any(), any(), eq("RESTAURANT_OPS_WASTAGE_TOP"), any()))
                .thenThrow(new IOException("downstream unavailable"));
        RestaurantOpsGoldAnalysisTool tool = new RestaurantOpsGoldAnalysisTool(gold);

        assertThatThrownBy(() -> tool.doExecute(
                "DEMO_REST",
                Map.of(
                        "userInput", "最近哪些食材盘亏最严重？",
                        "intentCode", "RESTAURANT_OPS_WASTAGE_TOP"),
                Collections.emptyMap()))
                .isInstanceOf(IOException.class)
                .hasMessage("downstream unavailable");
    }

    @Test
    @DisplayName("指定门店作用域保留鉴权租户并转发结构化上下文")
    void storeScopedDemoPreservesAuthFactoryAndForwardsContext() throws Exception {
        GoldFinanceClient gold = mock(GoldFinanceClient.class);
        Map<String, Object> expectedContext = Map.of(
                "store_id", "44",
                "store_name", "鲜行者打浦桥日月光店");
        when(gold.fetchRestaurantOpsAnalysis(
                eq("DEMO_REST"), any(), any(),
                eq("RESTAURANT_OPS_STORE_MARGIN"), eq(expectedContext)))
                .thenReturn(Map.of(
                        "success", true,
                        "answer", "鲜行者打浦桥日月光店的毛利率排名结果已生成。"));

        RestaurantOpsGoldAnalysisTool tool = new RestaurantOpsGoldAnalysisTool(gold);
        Map<String, Object> result = tool.doExecute(
                "DEMO_REST",
                Map.of(
                        "userInput", "那它的毛利率也是第一吗？",
                        "intentCode", "RESTAURANT_OPS_STORE_MARGIN",
                        "store_id", "44",
                        "store_name", "鲜行者打浦桥日月光店"),
                Collections.emptyMap());

        assertThat(result).containsEntry("dataAvailable", true);
        verify(gold).fetchRestaurantOpsAnalysis(
                eq("DEMO_REST"), any(), any(),
                eq("RESTAURANT_OPS_STORE_MARGIN"), eq(expectedContext));
    }

    @Test
    @DisplayName("指定门店下游不可用时不得换成营业额或全店榜")
    void scopedStoreOutageNamesTargetAndDoesNotSubstituteGlobalRanking() throws Exception {
        GoldFinanceClient gold = mock(GoldFinanceClient.class);
        when(gold.fetchRestaurantOpsAnalysis(
                eq("DEMO_REST"), any(), any(),
                eq("RESTAURANT_OPS_STORE_MARGIN"), any()))
                .thenThrow(new IOException("downstream unavailable"));
        RestaurantOpsGoldAnalysisTool tool = new RestaurantOpsGoldAnalysisTool(gold);

        Map<String, Object> result = tool.doExecute(
                "DEMO_REST",
                Map.of(
                        "userInput", "那它的毛利率也是第一吗？",
                        "intentCode", "RESTAURANT_OPS_STORE_MARGIN",
                        "store_id", "44",
                        "store_name", "鲜行者打浦桥日月光店"),
                Collections.emptyMap());

        assertThat(result).containsEntry("dataAvailable", false);
        assertThat(result.get("answer").toString())
                .contains("鲜行者打浦桥日月光店", "毛利率", "不会用营业额或其他门店榜单替代")
                .doesNotContain("Python", "Gold", "restaurant_ops", "IOException");
    }

    @Test
    @DisplayName("结构化双日期下游不可用时严格沿用两个绝对日期")
    void structuredComparisonOutageUsesExactDates() throws Exception {
        GoldFinanceClient gold = mock(GoldFinanceClient.class);
        when(gold.fetchRestaurantOpsAnalysis(
                eq("DEMO_REST"), any(), any(),
                eq("RESTAURANT_OPS_GROSS_MARGIN"), any()))
                .thenThrow(new IOException("downstream unavailable"));
        RestaurantOpsGoldAnalysisTool tool = new RestaurantOpsGoldAnalysisTool(gold);

        Map<String, Object> result = tool.doExecute(
                "DEMO_REST",
                Map.of(
                        "userInput", "那毛利呢？请沿用刚才比较的两个日期。",
                        "intentCode", "RESTAURANT_OPS_GROSS_MARGIN",
                        "startDate", "2026-07-20",
                        "endDate", "2026-07-20",
                        "comparisonStartDate", "2026-07-19",
                        "comparisonEndDate", "2026-07-19",
                        "timeAnchorDate", "2026-07-21"),
                Collections.emptyMap());

        assertThat(result).containsEntry("dataAvailable", false);
        assertThat(result.get("answer").toString())
                .contains("2026-07-20", "2026-07-19", "毛利", "不会用其他日期、营业额或其他指标替代")
                .doesNotContain("Python", "Gold", "restaurant_ops", "IOException");
    }
    private static Map<String, Object> executeWithTransportFailure(
            String question,
            String intentCode) throws Exception {
        GoldFinanceClient gold = mock(GoldFinanceClient.class);
        when(gold.fetchRestaurantOpsAnalysis(eq("DEMO_REST"), any(), any(), eq(intentCode), any()))
                .thenThrow(new IOException("downstream unavailable"));
        RestaurantOpsGoldAnalysisTool tool = new RestaurantOpsGoldAnalysisTool(gold);
        return tool.doExecute(
                "DEMO_REST",
                Map.of("userInput", question, "intentCode", intentCode),
                Collections.emptyMap());
    }
}
