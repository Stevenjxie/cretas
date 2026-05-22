package com.cretas.aims.ai.tool.impl.restaurant;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.impl.restaurant.diagnostic.RestaurantCostRigidityAnalysisTool;
import com.cretas.aims.ai.tool.impl.restaurant.diagnostic.RestaurantShrinkageAnalysisTool;
import com.cretas.aims.ai.tool.impl.restaurant.diagnostic.RestaurantStorePnlOnePagerTool;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RestaurantEconomicsAnalysisTool} — Sprint 11 Composite Tool.
 *
 * <p>Covers:
 * <ul>
 *   <li>Metadata (toolName / description)</li>
 *   <li>Happy path: all 3 sub-Tools succeed → dataAvailable=true + full evidence</li>
 *   <li>Steve 决策 1 (failure isolation): each sub-Tool can fail independently
 *       → dataAvailable=false + message contains "数据获取失败"</li>
 *   <li>Partial failure: 1 sub-Tool fails → others still produce data, evidence captures error</li>
 *   <li>Throw path: sub-Tool throws Exception → caught, not propagated</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class RestaurantEconomicsAnalysisToolTest {

    private static final String FACTORY_ID = "RES_3101_009";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private RestaurantStorePnlOnePagerTool storePnlTool;

    @Mock
    private RestaurantShrinkageAnalysisTool shrinkageTool;

    @Mock
    private RestaurantCostRigidityAnalysisTool costRigidityTool;

    private RestaurantEconomicsAnalysisTool tool;

    @BeforeEach
    void setUp() throws Exception {
        tool = new RestaurantEconomicsAnalysisTool(storePnlTool, shrinkageTool, costRigidityTool);
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-REA-01: metadata — toolName / description / schema")
    void metadata() {
        assertThat(tool.getToolName()).isEqualTo("restaurant_economics_analysis");
        assertThat(tool.getDescription())
                .contains("餐厅经营分析")
                .contains("一句话查损益")
                .contains("数据不可用");
        assertThat(tool.getParametersSchema()).containsKey("properties");
    }

    @Test
    @DisplayName("UT-REA-02: happy path — all 3 sub-Tools succeed → dataAvailable=true")
    void happyPath_allSubToolsSucceed() throws Exception {
        when(storePnlTool.getToolName()).thenReturn("restaurant_store_pnl_one_pager");
        when(shrinkageTool.getToolName()).thenReturn("restaurant_shrinkage_analysis");
        when(costRigidityTool.getToolName()).thenReturn("restaurant_cost_rigidity_analysis");

        when(storePnlTool.execute(any(ToolCall.class), any()))
                .thenReturn(buildSuccessJson(Map.of(
                        "success", true,
                        "section", "store_pnl_one_pager",
                        "data", Map.of("营收", 731048, "净利率", -3.2),
                        "warnings", List.of(),
                        "followUpChips", List.of())));
        when(shrinkageTool.execute(any(ToolCall.class), any()))
                .thenReturn(buildSuccessJson(Map.of(
                        "success", true,
                        "section", "shrinkage_analysis",
                        "data", Map.of("topOffenders", List.of("烤鱼档", "凉菜档")),
                        "warnings", List.of(),
                        "followUpChips", List.of())));
        when(costRigidityTool.execute(any(ToolCall.class), any()))
                .thenReturn(buildSuccessJson(Map.of(
                        "success", true,
                        "section", "cost_rigidity",
                        "data", Map.of("costRigidity", 0.85, "severity", "moderate"),
                        "warnings", List.of(),
                        "followUpChips", List.of())));

        Map<String, Object> result = tool.doExecute(FACTORY_ID, Map.of("sub_sector", "火锅"), ctx());

        assertThat(result).containsEntry("dataAvailable", true);
        assertThat(result).containsKey("summary");
        assertThat(result).containsKey("topItems");
        assertThat(result).containsKey("recommendations");
        assertThat(result).containsKey("evidence");

        Map<?, ?> summary = (Map<?, ?>) result.get("summary");
        assertThat(summary.get("dataAvailable")).isEqualTo(true);

        Map<?, ?> topItems = (Map<?, ?>) result.get("topItems");
        assertThat(topItems.get("dataAvailable")).isEqualTo(true);

        Map<?, ?> recommendations = (Map<?, ?>) result.get("recommendations");
        assertThat(recommendations.get("dataAvailable")).isEqualTo(true);

        assertThat(result.get("message").toString()).contains("全维度可用");
    }

    @Test
    @DisplayName("UT-REA-03: Steve 决策 1 — store_pnl 失败 → top dataAvailable=false + 失败标注")
    void storePnlFails_dataAvailableFalse() throws Exception {
        when(storePnlTool.getToolName()).thenReturn("restaurant_store_pnl_one_pager");
        when(shrinkageTool.getToolName()).thenReturn("restaurant_shrinkage_analysis");
        when(costRigidityTool.getToolName()).thenReturn("restaurant_cost_rigidity_analysis");

        // store_pnl Python returned success=false (data 不足) — inner success=false case
        when(storePnlTool.execute(any(ToolCall.class), any()))
                .thenReturn(buildSuccessJson(Map.of(
                        "success", false,
                        "message", "暂无法生成「单店P&L」分析: 未上传财务数据")));
        when(shrinkageTool.execute(any(ToolCall.class), any()))
                .thenReturn(buildSuccessJson(Map.of(
                        "success", true,
                        "section", "shrinkage_analysis",
                        "data", Map.of("topOffenders", List.of("凉菜档")),
                        "warnings", List.of(),
                        "followUpChips", List.of())));
        when(costRigidityTool.execute(any(ToolCall.class), any()))
                .thenReturn(buildSuccessJson(Map.of(
                        "success", true,
                        "section", "cost_rigidity",
                        "data", Map.of("costRigidity", 0.85),
                        "warnings", List.of(),
                        "followUpChips", List.of())));

        Map<String, Object> result = tool.doExecute(FACTORY_ID, Map.of(), ctx());

        // Top-level dataAvailable should be false (one sub-Tool failed)
        assertThat(result).containsEntry("dataAvailable", false);

        // summary should mark dataAvailable=false + carry message
        Map<?, ?> summary = (Map<?, ?>) result.get("summary");
        assertThat(summary.get("dataAvailable")).isEqualTo(false);
        assertThat(summary.get("message").toString()).contains("P&L 一页纸数据获取失败");

        // topItems still OK (per Steve 决策 1: 其他维度照说)
        Map<?, ?> topItems = (Map<?, ?>) result.get("topItems");
        assertThat(topItems.get("dataAvailable")).isEqualTo(true);

        // recommendations still OK
        Map<?, ?> recommendations = (Map<?, ?>) result.get("recommendations");
        assertThat(recommendations.get("dataAvailable")).isEqualTo(true);

        // Top message lists failed sub-Tools
        assertThat(result.get("message").toString())
                .contains("部分数据不可用")
                .contains("P&L 一页纸");
    }

    @Test
    @DisplayName("UT-REA-04: shrinkage throws Exception → caught + dataAvailable=false + others continue")
    void shrinkageThrows_dataAvailableFalse() throws Exception {
        when(storePnlTool.getToolName()).thenReturn("restaurant_store_pnl_one_pager");
        when(shrinkageTool.getToolName()).thenReturn("restaurant_shrinkage_analysis");
        when(costRigidityTool.getToolName()).thenReturn("restaurant_cost_rigidity_analysis");

        when(storePnlTool.execute(any(ToolCall.class), any()))
                .thenReturn(buildSuccessJson(Map.of(
                        "success", true, "section", "store_pnl_one_pager",
                        "data", Map.of("营收", 100000),
                        "warnings", List.of(), "followUpChips", List.of())));
        when(shrinkageTool.execute(any(ToolCall.class), any()))
                .thenThrow(new RuntimeException("Python 服务超时"));
        when(costRigidityTool.execute(any(ToolCall.class), any()))
                .thenReturn(buildSuccessJson(Map.of(
                        "success", true, "section", "cost_rigidity",
                        "data", Map.of("costRigidity", 1.05),
                        "warnings", List.of(), "followUpChips", List.of())));

        Map<String, Object> result = tool.doExecute(FACTORY_ID, Map.of(), ctx());

        assertThat(result).containsEntry("dataAvailable", false);

        Map<?, ?> topItems = (Map<?, ?>) result.get("topItems");
        assertThat(topItems.get("dataAvailable")).isEqualTo(false);
        assertThat(topItems.get("message").toString()).contains("档口损溢数据获取失败");

        // store + cost_rigidity still rolled in
        Map<?, ?> summary = (Map<?, ?>) result.get("summary");
        assertThat(summary.get("dataAvailable")).isEqualTo(true);
        Map<?, ?> recommendations = (Map<?, ?>) result.get("recommendations");
        assertThat(recommendations.get("dataAvailable")).isEqualTo(true);
    }

    @Test
    @DisplayName("UT-REA-05: all 3 sub-Tools fail → dataAvailable=false + 3 failure markers in evidence")
    void allSubToolsFail() throws Exception {
        when(storePnlTool.getToolName()).thenReturn("restaurant_store_pnl_one_pager");
        when(shrinkageTool.getToolName()).thenReturn("restaurant_shrinkage_analysis");
        when(costRigidityTool.getToolName()).thenReturn("restaurant_cost_rigidity_analysis");

        when(storePnlTool.execute(any(ToolCall.class), any()))
                .thenReturn(buildSuccessJson(Map.of(
                        "success", false, "message", "no data")));
        when(shrinkageTool.execute(any(ToolCall.class), any()))
                .thenThrow(new RuntimeException("Python down"));
        when(costRigidityTool.execute(any(ToolCall.class), any()))
                .thenReturn(buildSuccessJson(Map.of(
                        "success", false, "message", "missing financial_data.previous")));

        Map<String, Object> result = tool.doExecute(FACTORY_ID, Map.of(), ctx());

        assertThat(result).containsEntry("dataAvailable", false);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> evidence = (List<Map<String, Object>>) result.get("evidence");
        assertThat(evidence).hasSize(3);
        assertThat(evidence).allMatch(e -> Boolean.FALSE.equals(e.get("dataAvailable")));
        assertThat(evidence).allMatch(e -> e.get("error") != null);
    }

    // ---------- helpers ----------

    /**
     * Build the JSON envelope that {@link com.cretas.aims.ai.tool.AbstractTool#buildSuccessResult}
     * would produce for a sub-Tool. The composite Tool parses this as { success, data }.
     */
    private String buildSuccessJson(Map<String, Object> data) throws Exception {
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("success", true);
        envelope.put("data", data);
        return objectMapper.writeValueAsString(envelope);
    }

    private Map<String, Object> ctx() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("factoryId", FACTORY_ID);
        ctx.put("userId", 1L);
        return ctx;
    }

    private void injectField(Object target, String name, Object value) throws Exception {
        Field f = findField(target.getClass(), name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private Field findField(Class<?> clazz, String name) {
        while (clazz != null) {
            try {
                return clazz.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new IllegalArgumentException("Field not found: " + name);
    }
}
