package com.cretas.aims.ai.tool.impl.restaurant;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.impl.restaurant.diagnostic.RestaurantCostRigidityAnalysisTool;
import com.cretas.aims.ai.tool.impl.restaurant.diagnostic.RestaurantShrinkageAnalysisTool;
import com.cretas.aims.ai.tool.impl.restaurant.diagnostic.RestaurantStorePnlOnePagerTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Sprint 12 餐饮 backend Phase A.3 — Composite Tool baseline integration test.
 *
 * <p><b>Why this shape (and not a {@code @SpringBootTest} + mock JWT filter):</b>
 * the Phase A.2 baseline ({@code docs/audits/sprint-12-mealclaw-backend-baseline.md}
 * §1.1) recorded that a live {@code curl} of the Composite Tool was "阻于 JWT"
 * and deferred a baseline to Phase A.3. A full Spring-context test that boots the
 * intent→tool pipeline depends on Python (8083), Redis, PostgreSQL and DashScope
 * keys — the established project pattern marks those {@code @Disabled} (see
 * {@link com.cretas.aims.integration.RestaurantP35IntegrationTest}). They never
 * run in CI, so they would NOT give Phase A.3 a green baseline.
 *
 * <p>This test instead drives the real {@link RestaurantEconomicsAnalysisTool}
 * orchestration with the three sub-Tools mocked, which is CI-reliable and
 * directly locks in the contract Phase A.2 wanted to verify:
 * <ul>
 *   <li>the 4-section result shape ({@code summary} / {@code topItems} /
 *       {@code recommendations} / {@code evidence})</li>
 *   <li>Steve 决策 1 — a failed sub-Tool is isolated: its section carries an
 *       "X 数据获取失败" message, the other sections still render, and the
 *       top-level {@code dataAvailable} flips to {@code false} with a
 *       "部分数据不可用" hint (no fabrication)</li>
 *   <li>the Phase C scope-out — when cost_rigidity returns inner
 *       {@code success=false} ("成本刚性数据不可用"), the Composite degrades
 *       gracefully rather than throwing</li>
 * </ul>
 *
 * <p>Auth is intentionally out of scope here — {@code doExecute} runs below the
 * security layer, so mocking the sub-Tools removes the JWT blocker entirely
 * (which was the Phase A.2 ask) without a brittle filter mock.
 */
@ExtendWith(MockitoExtension.class)
class RestaurantEconomicsAnalysisToolIntegrationTest {

    private static final String FACTORY = "RES_3101_009";

    @Mock
    private RestaurantStorePnlOnePagerTool storePnlTool;
    @Mock
    private RestaurantShrinkageAnalysisTool shrinkageTool;
    @Mock
    private RestaurantCostRigidityAnalysisTool costRigidityTool;
    /**
     * Tiered-delegate gate that now fronts {@code doExecute}. Stubbed to {@code null}
     * ("Python did not answer") so the Composite runs its own 3-sub-Tool orchestration,
     * which is what every assertion in this class is about.
     *
     * <p>The explicit {@code null} stub is required: Mockito's default answer hands back
     * an <i>empty Map</i> for {@code Map}-returning methods, which is non-null and would
     * make {@code doExecute} return that empty map instead of the composed result.
     */
    @Mock
    private TieredIntentDelegate tieredDelegate;

    private RestaurantEconomicsAnalysisTool tool;

    @BeforeEach
    void setUp() {
        tool = new RestaurantEconomicsAnalysisTool(storePnlTool, shrinkageTool, costRigidityTool);
        // AbstractTool.objectMapper is normally @Autowired; inject a real one for the test.
        ReflectionTestUtils.setField(tool, "objectMapper", new ObjectMapper());
        // @Autowired field on the Tool — without it doExecute NPEs at the delegate gate.
        ReflectionTestUtils.setField(tool, "tieredDelegate", tieredDelegate);
        lenient().when(tieredDelegate.tryDelegate(
                        any(), org.mockito.ArgumentMatchers.anyMap(),
                        org.mockito.ArgumentMatchers.anyMap(), any()))
                .thenReturn(null);

        // getToolName() is read by the Composite when building synthetic ToolCalls.
        lenient().when(storePnlTool.getToolName()).thenReturn("restaurant_store_pnl_one_pager");
        lenient().when(shrinkageTool.getToolName()).thenReturn("restaurant_shrinkage_analysis");
        lenient().when(costRigidityTool.getToolName()).thenReturn("restaurant_cost_rigidity_analysis");
    }

    /** Outer wrapper (AbstractBusinessTool.buildSuccessResult) wrapping an inner section payload. */
    private String availableSection(String section, String sampleKey, Object sampleValue) {
        return "{\"success\":true,\"data\":{\"success\":true,\"section\":\"" + section
                + "\",\"data\":{\"" + sampleKey + "\":" + toJson(sampleValue)
                + "},\"warnings\":[]}}";
    }

    /** Inner success=false — the canonical "X 数据不可用" skip path. */
    private String unavailableSection(String message) {
        return "{\"success\":true,\"data\":{\"success\":false,\"message\":\"" + message + "\"}}";
    }

    private String toJson(Object v) {
        return v instanceof String ? "\"" + v + "\"" : String.valueOf(v);
    }

    private Map<String, Object> ctx() {
        Map<String, Object> c = new HashMap<>();
        c.put("factoryId", FACTORY);
        c.put("userId", 22);
        return c;
    }

    @Test
    void allThreeSubToolsAvailable_returnsFullStructure() throws Exception {
        when(storePnlTool.execute(any(ToolCall.class), any()))
                .thenReturn(availableSection("store_pnl_one_pager", "revenue", 1935193.0));
        when(shrinkageTool.execute(any(ToolCall.class), any()))
                .thenReturn(availableSection("shrinkage_analysis", "categoryCount", 4));
        when(costRigidityTool.execute(any(ToolCall.class), any()))
                .thenReturn(availableSection("cost_rigidity", "rigidity", 1.2));

        Map<String, Object> result = tool.doExecute(FACTORY, new HashMap<>(), ctx());

        assertThat(result.get("dataAvailable")).isEqualTo(true);
        assertThat(result).containsKeys("summary", "topItems", "recommendations", "evidence");

        assertSectionAvailable(result, "summary");
        assertSectionAvailable(result, "topItems");
        assertSectionAvailable(result, "recommendations");

        assertThat(result.get("message").toString()).contains("全维度可用");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> evidence = (List<Map<String, Object>>) result.get("evidence");
        assertThat(evidence).hasSize(3);
        assertThat(evidence).allSatisfy(e -> assertThat(e.get("dataAvailable")).isEqualTo(true));
    }

    @Test
    void costRigidityUnavailable_isolatesFailureKeepsOthers() throws Exception {
        // Phase C scope-out: cost_rigidity returns inner success=false ("成本刚性数据不可用").
        when(storePnlTool.execute(any(ToolCall.class), any()))
                .thenReturn(availableSection("store_pnl_one_pager", "revenue", 1935193.0));
        when(shrinkageTool.execute(any(ToolCall.class), any()))
                .thenReturn(availableSection("shrinkage_analysis", "categoryCount", 4));
        when(costRigidityTool.execute(any(ToolCall.class), any()))
                .thenReturn(unavailableSection("成本刚性数据不可用"));

        Map<String, Object> result = tool.doExecute(FACTORY, new HashMap<>(), ctx());

        // Steve 决策 1 + S13-001 (#312): top-level reflects PARTIAL availability — summary +
        // topItems have data, so dataAvailable=true; the failed cost_rigidity dimension stays
        // marked unavailable in its own section + listed in the message (was AND-of-all pre-#312).
        assertThat(result.get("dataAvailable")).isEqualTo(true);
        assertSectionAvailable(result, "summary");
        assertSectionAvailable(result, "topItems");

        @SuppressWarnings("unchecked")
        Map<String, Object> recommendations = (Map<String, Object>) result.get("recommendations");
        assertThat(recommendations.get("dataAvailable")).isEqualTo(false);
        assertThat(recommendations.get("message").toString()).contains("成本刚性数据获取失败");

        assertThat(result.get("message").toString())
                .contains("部分数据不可用").contains("成本刚性");
    }

    @Test
    void subToolThrows_isolatedNotPropagated() throws Exception {
        when(storePnlTool.execute(any(ToolCall.class), any()))
                .thenReturn(availableSection("store_pnl_one_pager", "revenue", 1935193.0));
        when(shrinkageTool.execute(any(ToolCall.class), any()))
                .thenThrow(new RuntimeException("DB connection refused"));
        when(costRigidityTool.execute(any(ToolCall.class), any()))
                .thenReturn(availableSection("cost_rigidity", "rigidity", 1.2));

        // Must NOT throw — exception is isolated to the shrinkage section.
        Map<String, Object> result = tool.doExecute(FACTORY, new HashMap<>(), ctx());

        // S13-001 (#312): partial availability — summary + recommendations have data → dataAvailable=true;
        // the thrown shrinkage dimension stays marked unavailable in its own section.
        assertThat(result.get("dataAvailable")).isEqualTo(true);
        assertSectionAvailable(result, "summary");
        assertSectionAvailable(result, "recommendations");

        @SuppressWarnings("unchecked")
        Map<String, Object> topItems = (Map<String, Object>) result.get("topItems");
        assertThat(topItems.get("dataAvailable")).isEqualTo(false);
        assertThat(topItems.get("message").toString()).contains("档口损溢数据获取失败");
    }

    @Test
    void allUnavailable_dataAvailableFalseNoThrow() throws Exception {
        when(storePnlTool.execute(any(ToolCall.class), any()))
                .thenReturn(unavailableSection("P&L 数据不可用"));
        when(shrinkageTool.execute(any(ToolCall.class), any()))
                .thenReturn(unavailableSection("损溢数据不可用"));
        when(costRigidityTool.execute(any(ToolCall.class), any()))
                .thenReturn(unavailableSection("成本刚性数据不可用"));

        Map<String, Object> result = tool.doExecute(FACTORY, new HashMap<>(), ctx());

        // S13-001 (#312): NONE available is the only case that stays dataAvailable=false, and it
        // uses the distinct none-available template (not the partial "部分数据不可用" failed-list).
        // Message now explains cause + next-action (防呆 Rule 5) instead of a bare "均无数据".
        assertThat(result.get("dataAvailable")).isEqualTo(false);
        assertThat(result.get("message").toString())
                .contains("暂不可用").contains("档口损溢").contains("成本刚性").contains("Excel上传");
        assertThat(result.get("actionHint").toString()).contains("Excel上传");
    }

    @SuppressWarnings("unchecked")
    private void assertSectionAvailable(Map<String, Object> result, String section) {
        Map<String, Object> node = (Map<String, Object>) result.get(section);
        assertThat(node.get("dataAvailable")).isEqualTo(true);
        assertThat(node).containsKey("data");
    }
}
