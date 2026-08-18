package com.cretas.aims.service.production;

import com.cretas.aims.dto.production.ProductionSettlementPrefillResponse;
import com.cretas.aims.dto.production.ProductionSettlementRequest;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * 闸 —— App 报工那条腿也必须给出<b>完整的</b> {@code terminalOutputs}，
 * 否则 workflow 计划结不了单。
 *
 * <h2>🔴 为什么有这道闸 (2026-08-18 prod 实测, 两轮)</h2>
 *
 * {@code getSettlementPrefill} 有两条腿：web 逐道电子表格、App 逐道报工。
 * <b>web 那条建了 terminalOutputs，App 这条没建</b>（{@code @Builder.Default} → 空 list），
 * 于是 {@code persistWorkflowSettlementOutputs} 看到 {@code reportedOutputs.isEmpty()}
 * 就抛 {@code 409 WORKFLOW_OUTPUT_SET_MISMATCH}。
 *
 * <p>⚠️ <b>第二轮</b>：补上 SKU/数量/单位之后，报错从 {@code WORKFLOW_OUTPUT_SET_MISMATCH}
 * 变成 {@code WORKFLOW_OUTPUT_LINE_INVALID}（「line is incomplete」）——
 * <b>拒答从一道闸挪到了下一道</b>（本仓硬约束 8 的原形）。缺的是：
 * <ol>
 *   <li>{@code batchNumber}（下游写进 {@code reportedBatchNumber}）</li>
 *   <li>{@code materialNodeId}，且必须等于配方钉住的 {@code targetTerminalNodeId}</li>
 * </ol>
 *
 * <p>🔴 <b>不能拿工序任务上的 {@code workflowNodeId} 去填</b>：那是<b>工序</b>节点，
 * 配方钉的是<b>物料</b>节点，两者不是一个东西（prod 实测
 * {@code process:36be0c48-…:1786933141612} vs {@code material:finished:1786933141612}），
 * 直接比会永远不等。所以物料节点从既有权威 {@code resolvePinnedTerminalNodes} 取。
 *
 * <h2>这道闸钉三层</h2>
 * <ol>
 *   <li><b>行为</b>：末道报工 → 按 (SKU, 批次号, 单位) 分组求和；不跨单位相加；空 SKU 跳过</li>
 *   <li><b>完整性</b>：批次号、末道物料节点都要带上 —— 少一个下游就 409</li>
 *   <li><b>接线</b>：那条腿的 prefill builder 里必须真的有 {@code .terminalOutputs(...)}
 *       —— helper 写对了不等于接上了（本仓形态 B / C″）</li>
 * </ol>
 */
class AppReportTerminalOutputsContractTest {

    private static final String FG = "eb0aa47b-a5dd-49dc-af20-bf48ce8e1207";
    private static final String OTHER = "PT_OTHER";
    private static final String NODE = "material:finished:1786933141612";
    private static final String BN = "PB-PLAN-1786954657305-A356E80A-56231";
    private static final Path SRC = Path.of(
            "src/main/java/com/cretas/aims/service/impl/ProductionPlanServiceImpl.java");

    /**
     * ⛔ 不打桩 {@code resolvePinnedTerminalNodes} —— 它是 private, 而且打了桩就等于
     * 把「末道节点是怎么定出来的」这件事从闸里挖掉了。这里装上真的 {@code BomRecipeRepository},
     * 让它跑真实实现 (本仓形态 B: 断言要跑在产品真实入口上)。
     */
    private static ProductionPlanServiceImpl service(Map<String, String> pinned) {
        ProductionPlanServiceImpl svc = mock(ProductionPlanServiceImpl.class,
                withSettings().defaultAnswer(CALLS_REAL_METHODS));
        BomRecipeRepository repo = mock(BomRecipeRepository.class);
        for (Map.Entry<String, String> e : pinned.entrySet()) {
            BomRecipe recipe = new BomRecipe();
            recipe.setId("recipe-" + e.getKey());
            recipe.setFactoryId("F006");
            recipe.setProductTypeId(e.getKey());
            recipe.setVersion(1);
            recipe.setBomFamilyId("fam-1");
            recipe.setWorkflowRevisionId(9001L);
            recipe.setWorkflowRevisionHash("hash-1");
            recipe.setTargetTerminalNodeId(e.getValue());
            when(repo.findById("recipe-" + e.getKey())).thenReturn(Optional.of(recipe));
        }
        ReflectionTestUtils.setField(svc, "bomRecipeRepository", repo);
        return svc;
    }

    private static Map<String, String> pinnedFg() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(FG, NODE);
        return m;
    }

    /** 非 workflow 计划(不钉 workflow, 也没有 recipe 映射)。 */
    private static ProductionPlan plan(String productTypeId) {
        ProductionPlan p = new ProductionPlan();
        p.setId("plan-1");
        p.setProductTypeId(productTypeId);
        return p;
    }

    /** workflow 计划: 把 pinned 里的每个 SKU 都钉上对应的 recipe。 */
    private static ProductionPlan workflowPlan(String productTypeId, Map<String, String> pinned) {
        ProductionPlan p = plan(productTypeId);
        p.setWorkflowSelectionMode(ProductionBatch.WorkflowSelectionMode.WORKFLOW);
        p.setSelectedBomFamilyId("fam-1");
        p.setSelectedWorkflowRevisionId(9001L);
        p.setSelectedWorkflowRevisionHash("hash-1");
        Map<String, String> ids = new LinkedHashMap<>();
        Map<String, Integer> versions = new LinkedHashMap<>();
        for (String sku : pinned.keySet()) {
            ids.put(sku, "recipe-" + sku);
            versions.put(sku, 1);
        }
        p.setSelectedBomRecipeIdsByProduct(ids);
        p.setSelectedBomVersionsByProduct(versions);
        return p;
    }

    private static ProductionBatch batch(Long id, String batchNumber) {
        ProductionBatch b = new ProductionBatch();
        b.setId(id);
        b.setBatchNumber(batchNumber);
        return b;
    }

    private static ProductionReport report(Integer order, String sku, String qty, String unit) {
        return report(order, sku, qty, unit, 10761L);
    }

    private static ProductionReport report(Integer order, String sku, String qty, String unit, Long batchId) {
        ProductionReport r = new ProductionReport();
        r.setProcessOrder(order);
        r.setProductTypeId(sku);
        r.setOutputQuantity(qty == null ? null : new BigDecimal(qty));
        r.setOutputUnit(unit);
        r.setBatchId(batchId);
        return r;
    }

    private final List<ProductionSettlementPrefillResponse.Issue> issues = new ArrayList<>();

    @SuppressWarnings("unchecked")
    private List<ProductionSettlementRequest.OutputLine> derive(
            Map<String, String> pinned, ProductionPlan plan, List<ProductionReport> reports) {
        return (List<ProductionSettlementRequest.OutputLine>) ReflectionTestUtils.invokeMethod(
                service(pinned), "deriveTerminalOutputsFromReports",
                "F006", plan, List.of(batch(10761L, BN)), reports, issues);
    }

    private List<ProductionSettlementRequest.OutputLine> derive(
            ProductionPlan plan, List<ProductionReport> reports) {
        return derive(pinnedFg(), plan, reports);
    }

    private boolean hasIssue(String code) {
        return issues.stream().anyMatch(i -> code.equals(i.getCode()));
    }

    @Test
    @DisplayName("阳性对照: 末道报工能推出【完整的】产出行 (否则下面全是恒真)")
    void terminalReportProducesACompleteOutputLine() {
        List<ProductionSettlementRequest.OutputLine> out = derive(
                workflowPlan(FG, pinnedFg()), List.of(report(1, FG, "60", "kg"), report(2, FG, "75", "盒")));
        assertEquals(1, out.size(), "实际 " + out.size());
        ProductionSettlementRequest.OutputLine line = out.get(0);
        assertEquals(FG, line.getProductTypeId());
        assertEquals("盒", line.getUnit(), "取的不是末道那一道");
        assertEquals(0, line.getQuantity().compareTo(new BigDecimal("75")));
        // 🔴 下面两条是第二轮加的: 少任何一个, prod 上都是 409 WORKFLOW_OUTPUT_LINE_INVALID
        assertEquals(BN, line.getBatchNumber(), "没带批次号 → 下游 WORKFLOW_OUTPUT_LINE_INVALID");
        assertEquals(NODE, line.getMaterialNodeId(), "没带末道物料节点 → 下游 WORKFLOW_TERMINAL_NODE_MISMATCH");
    }

    @Test
    @DisplayName("🔴 只取末道 —— 中间道的产出不许混进来(那是半成品, 不是成品)")
    void onlyTheLastProcessCounts() {
        List<ProductionSettlementRequest.OutputLine> out = derive(
                workflowPlan(FG, pinnedFg()), List.of(report(1, FG, "60", "kg"), report(2, FG, "75", "盒")));
        assertTrue(out.stream().noneMatch(l -> "kg".equals(l.getUnit())),
                "把工序① 的半成品也算成末道产出了: " + out);
    }

    @Test
    @DisplayName("同一道多次报工按 (SKU,批次,单位) 求和")
    void sameSkuBatchAndUnitAreSummed() {
        List<ProductionSettlementRequest.OutputLine> out = derive(
                workflowPlan(FG, pinnedFg()), List.of(report(2, FG, "40", "盒"), report(2, FG, "35", "盒")));
        assertEquals(1, out.size());
        assertEquals(0, out.get(0).getQuantity().compareTo(new BigDecimal("75")));
    }

    @Test
    @DisplayName("🔴 阴性对照: 不跨单位相加 —— 同 SKU 出现两个单位要各自成行")
    void differentUnitsAreNotSummed() {
        List<ProductionSettlementRequest.OutputLine> out = derive(
                workflowPlan(FG, pinnedFg()), List.of(report(2, FG, "40", "盒"), report(2, FG, "5", "kg")));
        assertEquals(2, out.size(), "把 盒 和 kg 加到一起了: " + out);
    }

    @Test
    @DisplayName("🔴 阴性对照: 不同生产批次不许合并成一行(批次号是产出的身份)")
    void differentBatchesAreNotMerged() {
        ProductionPlanServiceImpl svc = service(pinnedFg());
        @SuppressWarnings("unchecked")
        List<ProductionSettlementRequest.OutputLine> out =
                (List<ProductionSettlementRequest.OutputLine>) ReflectionTestUtils.invokeMethod(
                        svc, "deriveTerminalOutputsFromReports", "F006", workflowPlan(FG, pinnedFg()),
                        List.of(batch(10761L, BN), batch(10762L, "PB-OTHER-0002")),
                        List.of(report(2, FG, "40", "盒", 10761L), report(2, FG, "35", "盒", 10762L)),
                        issues);
        assertEquals(2, out.size(), "两个生产批次的产出被并成一行了: " + out);
    }

    @Test
    @DisplayName("🔴 不在钉住集合里的 SKU 不许出线, 而且要明说是哪个(不静默丢)")
    void skuOutsideThePinnedTerminalSetIsExcludedLoudly() {
        List<ProductionSettlementRequest.OutputLine> out = derive(
                workflowPlan(FG, pinnedFg()), List.of(report(2, OTHER, "75", "盒")));
        assertTrue(out.isEmpty(), "把没钉住的 SKU 当成了成品产出: " + out);
        assertTrue(hasIssue("WORKFLOW_OUTPUT_SKU_NOT_PINNED"), "静默丢掉了, 没有任何 issue: " + issues);
    }

    @Test
    @DisplayName("找不到生产批次号时不出线, 并明说(下游需要它写 reportedBatchNumber)")
    void missingBatchNumberIsReportedNotFabricated() {
        ProductionPlanServiceImpl svc = service(pinnedFg());
        @SuppressWarnings("unchecked")
        List<ProductionSettlementRequest.OutputLine> out =
                (List<ProductionSettlementRequest.OutputLine>) ReflectionTestUtils.invokeMethod(
                        svc, "deriveTerminalOutputsFromReports", "F006", workflowPlan(FG, pinnedFg()),
                        List.of(batch(99999L, "PB-UNRELATED")),
                        List.of(report(2, FG, "75", "盒", 10761L)), issues);
        assertTrue(out.isEmpty(), "凭空造了个批次号: " + out);
        assertTrue(hasIssue("WORKFLOW_OUTPUT_BATCH_MISSING"), "静默丢掉了: " + issues);
    }

    @Test
    @DisplayName("报工没带 SKU 时回落计划的产品; 两者都没有就跳过(空 SKU 会让集合比对恒不相等)")
    void skuFallsBackToPlanAndBlankIsSkipped() {
        assertEquals(FG, derive(workflowPlan(FG, pinnedFg()), List.of(report(2, null, "75", "盒")))
                .get(0).getProductTypeId());
        assertTrue(derive(plan(null), List.of(report(2, null, "75", "盒"))).isEmpty(),
                "造出了一条 SKU 为空的产出行");
    }

    @Test
    @DisplayName("数量为 0 / 负 / 空的报工不产出行; 空输入不炸")
    void nonPositiveQuantitiesAreSkipped() {
        assertTrue(derive(workflowPlan(FG, pinnedFg()), List.of(report(2, FG, "0", "盒"))).isEmpty());
        assertTrue(derive(workflowPlan(FG, pinnedFg()), List.of(report(2, FG, null, "盒"))).isEmpty());
        assertTrue(derive(workflowPlan(FG, pinnedFg()), new ArrayList<>()).isEmpty());
    }

    @Test
    @DisplayName("非 workflow 计划(没有钉住集合)照旧出线, materialNodeId 留空")
    void nonWorkflowPlanStillProducesLines() {
        List<ProductionSettlementRequest.OutputLine> out =
                derive(new LinkedHashMap<>(), plan(FG), List.of(report(2, FG, "75", "盒")));
        assertEquals(1, out.size(), "非 workflow 计划被误伤了: " + out);
        assertNotNull(out.get(0).getBatchNumber());
        assertNull(out.get(0).getMaterialNodeId(), "非 workflow 计划不该凭空造末道节点");
    }

    @Test
    @DisplayName("老数据无 processOrder → 退化为全部都算末道 (与 deriveLastStepOutput 同口径)")
    void reportsWithoutProcessOrderDegradeToAll() {
        Map<String, String> both = pinnedFg();
        both.put(OTHER, NODE);
        List<ProductionSettlementRequest.OutputLine> out = derive(
                both, workflowPlan(FG, both), List.of(report(null, FG, "40", "盒"), report(null, OTHER, "10", "盒")));
        assertEquals(2, out.size(), "实际 " + out);
    }

    @Test
    @DisplayName("🔴 接线闸: 那条腿的 prefill 必须真的带上 terminalOutputs —— helper 写对不等于接上了")
    void reportLegPrefillActuallyCarriesTerminalOutputs() throws Exception {
        String raw = Files.readString(SRC);
        // 先剥注释: 否则会数到讲这件事的那几段说明 (本仓形态 A⁗)
        String src = raw.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)^\\s*//.*$", "");
        int at = src.indexOf("deriveTerminalOutputsFromReports(factoryId, plan, batches, allReports, issues)");
        assertTrue(at > 0,
                "getSettlementPrefill 的报工腿没有调 deriveTerminalOutputsFromReports —— "
                        + "App 报完工的 workflow 计划又会结不了单");
        // 🔴 必须把范围钉死在【这条腿】的方法体内。
        //    第一版写的是 src.indexOf(".terminalOutputs(...)", at) —— 而 web 那条腿里也有同一行、
        //    且排在本方法之后, 于是匹配到了别人那处: 把调用点摘掉后闸【纹丝不动】。
        //    是变异把这个恒真式抓出来的 (本仓形态 A: 量的不是想知道的那个东西)。
        int legEnd = src.indexOf("return buildPrefillResponse(prefill, issues);", at);
        assertTrue(legEnd > at, "找不到这条腿的收尾, 范围钉不住");
        String leg = src.substring(at, legEnd);
        assertTrue(leg.contains(".terminalOutputs(terminalOutputs)"),
                "推出来了但没塞进【这条腿】的 prefill builder");
        assertTrue(leg.contains("resolveTerminalOutputUnit(terminalOutputs)"),
                "quantityUnit 仍留空 —— 结单单位会是空的");
    }
}
