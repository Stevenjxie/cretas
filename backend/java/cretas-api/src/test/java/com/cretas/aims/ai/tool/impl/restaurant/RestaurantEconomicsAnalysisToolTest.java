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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RestaurantEconomicsAnalysisTool} — Sprint 11 Composite Tool.
 *
 * <p>Covers:
 * <ul>
 *   <li>Metadata (toolName / description)</li>
 *   <li>Happy path: all 3 sub-Tools succeed → dataAvailable=true + full evidence</li>
 *   <li>Steve 决策 1 (failure isolation): each sub-Tool can fail independently; its data
 *       does NOT enter the narrative + message marks "X 数据获取失败"</li>
 *   <li>Sprint 13 (S13-001) partial-available: ≥1 sub-Tool has data → top-level
 *       dataAvailable=true (per-section flags still reflect each sub-Tool); only ALL-fail
 *       → dataAvailable=false</li>
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

    /**
     * Tiered-delegate gate that now fronts {@code doExecute}. Stubbed to {@code null}
     * ("Python did not answer") in {@link #setUp()} so the Composite runs its own
     * 3-sub-Tool orchestration, which is what the failure-isolation assertions below
     * are about; {@link #delegateHit_returnsPythonAnswerVerbatim()} overrides it with a hit.
     *
     * <p>The explicit {@code null} stub is required: Mockito's default answer hands back
     * an <i>empty Map</i> for {@code Map}-returning methods, which is non-null and would
     * make {@code doExecute} return that empty map instead of the composed result.
     */
    @Mock
    private TieredIntentDelegate tieredDelegate;

    private RestaurantEconomicsAnalysisTool tool;

    @BeforeEach
    void setUp() throws Exception {
        tool = new RestaurantEconomicsAnalysisTool(storePnlTool, shrinkageTool, costRigidityTool);
        injectField(tool, "objectMapper", objectMapper);
        // @Autowired field on the Tool — without it doExecute NPEs at the delegate gate.
        injectField(tool, "tieredDelegate", tieredDelegate);
        org.mockito.Mockito.lenient().when(tieredDelegate.tryDelegate(
                        any(), org.mockito.ArgumentMatchers.anyMap(),
                        org.mockito.ArgumentMatchers.anyMap(), any()))
                .thenReturn(null);
    }

    @Test
    @DisplayName("UT-REA-00: tiered delegate 命中 → 原样返回 Python 答案, 三个子工具不跑")
    void delegateHit_returnsPythonAnswerVerbatim() throws Exception {
        Map<String, Object> pythonAnswer = Map.of(
                "dataAvailable", true,
                "message", "本月净利率 -3.2%",
                "tieredDelegate", true);
        when(tieredDelegate.tryDelegate(
                org.mockito.ArgumentMatchers.eq(FACTORY_ID),
                org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.eq("restaurant_economics_analysis")))
                .thenReturn(pythonAnswer);

        Map<String, Object> result = tool.doExecute(FACTORY_ID, Map.of(), ctx());

        assertThat(result).isEqualTo(pythonAnswer);
        org.mockito.Mockito.verifyNoInteractions(storePnlTool, shrinkageTool, costRigidityTool);
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
    @DisplayName("UT-REA-03: S13-001 — store_pnl 失败 + 其他有数据 → top dataAvailable=true (partial) + 失败标注")
    void storePnlFails_partialAvailable() throws Exception {
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

        // Sprint 13 (S13-001): one sub-Tool failing does NOT drag top-level dataAvailable
        // to false when other dimensions have data (partial-available per Steve 决策 1).
        // Pre-S13-001 this asserted false — the bug that hid the whole ¥ P&L.
        assertThat(result).containsEntry("dataAvailable", true);

        // summary (store_pnl) still marks dataAvailable=false + carries its failure message
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
    @DisplayName("UT-REA-04: shrinkage throws Exception → caught + partial dataAvailable=true + others continue")
    void shrinkageThrows_partialAvailable() throws Exception {
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

        // Sprint 13 (S13-001): shrinkage throwing does NOT drag top-level to false —
        // store + cost_rigidity have data → partial-available.
        assertThat(result).containsEntry("dataAvailable", true);

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
    @DisplayName("UT-REA-05: S13-001 — ALL 3 sub-Tools fail → dataAvailable=false + 暂不可用+next-action + 3 failure markers")
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

        // Only when ALL dimensions fail is top-level dataAvailable false (S13-001).
        // None-available message now explains cause + next-action (防呆 Rule 5).
        assertThat(result).containsEntry("dataAvailable", false);
        assertThat(result.get("message").toString()).contains("暂不可用").contains("Excel上传");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> evidence = (List<Map<String, Object>>) result.get("evidence");
        assertThat(evidence).hasSize(3);
        assertThat(evidence).allMatch(e -> Boolean.FALSE.equals(e.get("dataAvailable")));
        assertThat(evidence).allMatch(e -> e.get("error") != null);
    }

    @Test
    @DisplayName("UT-REA-08: S13-001 prod case — only store_pnl has data (financial only) → top dataAvailable=true")
    void onlyStorePnlAvailable_partialDataAvailableTrue() throws Exception {
        // Real RES_3101_009 2025-12 prod scenario: financial P&L available (¥1.94M revenue)
        // but shrinkage + cost_rigidity scoped out. Top-level MUST be true so a consumer
        // keying on it does not hide the ¥ P&L (the S13-001 bug).
        when(storePnlTool.getToolName()).thenReturn("restaurant_store_pnl_one_pager");
        when(shrinkageTool.getToolName()).thenReturn("restaurant_shrinkage_analysis");
        when(costRigidityTool.getToolName()).thenReturn("restaurant_cost_rigidity_analysis");

        when(storePnlTool.execute(any(ToolCall.class), any()))
                .thenReturn(buildSuccessJson(Map.of(
                        "success", true, "section", "store_pnl_one_pager",
                        "data", Map.of("headline", "本店盈利 ¥1,541,082", "营收", 1935193),
                        "warnings", List.of(), "followUpChips", List.of())));
        when(shrinkageTool.execute(any(ToolCall.class), any()))
                .thenReturn(buildSuccessJson(Map.of(
                        "success", false, "message", "档口损溢数据不足")));
        when(costRigidityTool.execute(any(ToolCall.class), any()))
                .thenReturn(buildSuccessJson(Map.of(
                        "success", false, "message", "成本刚性数据不足")));

        Map<String, Object> result = tool.doExecute(FACTORY_ID, Map.of(), ctx());

        // Partial available: top-level true, summary (the ¥ P&L) true, failed dims listed.
        assertThat(result).containsEntry("dataAvailable", true);
        Map<?, ?> summary = (Map<?, ?>) result.get("summary");
        assertThat(summary.get("dataAvailable")).isEqualTo(true);
        assertThat(result.get("message").toString())
                .contains("部分数据不可用")
                .contains("档口损溢")
                .contains("成本刚性");
    }

    @Test
    @DisplayName("UT-REA-06: #302 — NL 'YYYY年M月' query scopes ALL 3 sub-Tools to that month")
    void nlHistoricalMonth_propagatesMonthToSubTools() throws Exception {
        when(storePnlTool.getToolName()).thenReturn("restaurant_store_pnl_one_pager");
        when(shrinkageTool.getToolName()).thenReturn("restaurant_shrinkage_analysis");
        when(costRigidityTool.getToolName()).thenReturn("restaurant_cost_rigidity_analysis");

        when(storePnlTool.execute(any(ToolCall.class), any()))
                .thenReturn(buildSuccessJson(Map.of(
                        "success", true, "section", "store_pnl_one_pager",
                        "data", Map.of("营收", 1935193),
                        "warnings", List.of(), "followUpChips", List.of())));
        when(shrinkageTool.execute(any(ToolCall.class), any()))
                .thenReturn(buildSuccessJson(Map.of(
                        "success", true, "section", "shrinkage_analysis",
                        "data", Map.of("topOffenders", List.of("凉菜档")),
                        "warnings", List.of(), "followUpChips", List.of())));
        when(costRigidityTool.execute(any(ToolCall.class), any()))
                .thenReturn(buildSuccessJson(Map.of(
                        "success", true, "section", "cost_rigidity",
                        "data", Map.of("costRigidity", 0.9),
                        "warnings", List.of(), "followUpChips", List.of())));

        // Owner asks about a historical month — the period must NOT silently fall back to 上月.
        Map<String, Object> params = new HashMap<>();
        params.put("userInput", "2025年12月哪个菜亏钱");
        tool.doExecute(FACTORY_ID, params, ctx());

        // All 3 sub-Tools receive the resolved month=2025-12 in their ToolCall arguments.
        ArgumentCaptor<ToolCall> pnlCaptor = ArgumentCaptor.forClass(ToolCall.class);
        ArgumentCaptor<ToolCall> shrinkCaptor = ArgumentCaptor.forClass(ToolCall.class);
        ArgumentCaptor<ToolCall> costCaptor = ArgumentCaptor.forClass(ToolCall.class);
        verify(storePnlTool).execute(pnlCaptor.capture(), any());
        verify(shrinkageTool).execute(shrinkCaptor.capture(), any());
        verify(costRigidityTool).execute(costCaptor.capture(), any());

        assertThat(monthArg(pnlCaptor)).isEqualTo("2025-12");
        assertThat(monthArg(shrinkCaptor)).isEqualTo("2025-12");
        assertThat(monthArg(costCaptor)).isEqualTo("2025-12");
    }

    @Test
    @DisplayName("UT-REA-07: #302 — resolveCompositeMonth precedence (param > startDate > NL absolute > NL relative)")
    void resolveCompositeMonth_precedence() {
        // explicit month param wins over NL hints
        assertThat(tool.resolveCompositeMonth(Map.of("month", "2025-03", "userInput", "本月营收")))
                .isEqualTo("2025-03");
        // preprocessed startDate ISO → yyyy-MM
        assertThat(tool.resolveCompositeMonth(Map.of("startDate", "2025-12-01", "userInput", "哪个菜亏钱")))
                .isEqualTo("2025-12");
        // NL absolute "YYYY年M月"
        assertThat(tool.resolveCompositeMonth(Map.of("userInput", "2025年12月哪个菜亏钱")))
                .isEqualTo("2025-12");
        // NL absolute "YYYY-MM" embedded mid-sentence
        assertThat(tool.resolveCompositeMonth(Map.of("userInput", "看一下 2025-12 的损益")))
                .isEqualTo("2025-12");
        // single-digit month zero-padded
        assertThat(tool.resolveCompositeMonth(Map.of("userInput", "2025年3月成本")))
                .isEqualTo("2025-03");
        // NL relative
        assertThat(tool.resolveCompositeMonth(Map.of("userInput", "上月成本分析"))).isEqualTo("上月");
        assertThat(tool.resolveCompositeMonth(Map.of("userInput", "本月哪个菜亏钱"))).isEqualTo("本月");
        // no signal → null (sub-Tools then apply their own 上月 default)
        assertThat(tool.resolveCompositeMonth(Map.of("userInput", "哪个菜亏钱"))).isNull();
        assertThat(tool.resolveCompositeMonth(Map.of())).isNull();
        assertThat(tool.resolveCompositeMonth(null)).isNull();
    }

    // ---------- helpers ----------

    /** Parse the captured sub-Tool {@link ToolCall} arguments and return the {@code month} value (or null). */
    private String monthArg(ArgumentCaptor<ToolCall> captor) throws Exception {
        ToolCall call = captor.getValue();
        Map<?, ?> args = objectMapper.readValue(call.getFunction().getArguments(), Map.class);
        Object month = args.get("month");
        return month == null ? null : month.toString();
    }

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
