package com.cretas.aims.service.production;

import com.cretas.aims.dto.production.ProductionSettlementRequest;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

/**
 * 闸 —— App 报工那条腿也必须给出 {@code terminalOutputs}，否则 workflow 计划结不了单。
 *
 * <h2>🔴 为什么有这道闸 (2026-08-18 prod 实测)</h2>
 *
 * {@code getSettlementPrefill} 有两条腿：web 逐道电子表格、App 逐道报工。
 * <b>web 那条建了 terminalOutputs，App 这条没建</b>（{@code @Builder.Default} → 空 list），
 * 于是 {@code persistWorkflowSettlementOutputs} 看到 {@code reportedOutputs.isEmpty()}
 * 就抛 {@code 409 WORKFLOW_OUTPUT_SET_MISMATCH}。
 *
 * <p>实测 F006 PLAN-1786954657305：工人在 App 把两道工都报完（任务 COMPLETED、审批 APPROVED），
 * 文员点「核对结单」仍然结不了单 —— 判据里那种「为什么点不动」。
 *
 * <p>⚠️ 那句报错还会误导人：说「提交的末道产出与计划固定的 Workflow 末道集合不一致」，
 * 听起来像用户传错了；实际是<b>服务端自己推导为空</b>（workflow 计划的结单请求会被
 * {@code deriveConfirmedSettlementRequest} 整个替换，客户端传什么都不参与判定）。
 * <b>判别实验</b>：不传 / 传对 / 传错 terminalOutputs，三次报错完全相同。
 *
 * <h2>这道闸钉两层</h2>
 * <ol>
 *   <li><b>行为</b>：末道报工 → 按 (SKU, 单位) 分组求和；不跨单位相加；空 SKU 跳过</li>
 *   <li><b>接线</b>：那条腿的 prefill builder 里必须真的有 {@code .terminalOutputs(...)}
 *       —— helper 写对了不等于接上了（本仓形态 B / C″）</li>
 * </ol>
 */
class AppReportTerminalOutputsContractTest {

    private static final String FG = "eb0aa47b-a5dd-49dc-af20-bf48ce8e1207";
    private static final String OTHER = "PT_OTHER";
    private static final Path SRC = Path.of(
            "src/main/java/com/cretas/aims/service/impl/ProductionPlanServiceImpl.java");

    private static ProductionPlanServiceImpl service() {
        return mock(ProductionPlanServiceImpl.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
    }

    private static ProductionPlan plan(String productTypeId) {
        ProductionPlan p = new ProductionPlan();
        p.setId("plan-1");
        p.setProductTypeId(productTypeId);
        return p;
    }

    private static ProductionReport report(Integer order, String sku, String qty, String unit) {
        ProductionReport r = new ProductionReport();
        r.setProcessOrder(order);
        r.setProductTypeId(sku);
        r.setOutputQuantity(qty == null ? null : new BigDecimal(qty));
        r.setOutputUnit(unit);
        return r;
    }

    @SuppressWarnings("unchecked")
    private static List<ProductionSettlementRequest.OutputLine> derive(
            ProductionPlan plan, List<ProductionReport> reports) {
        return (List<ProductionSettlementRequest.OutputLine>) ReflectionTestUtils.invokeMethod(
                service(), "deriveTerminalOutputsFromReports", plan, reports);
    }

    @Test
    @DisplayName("阳性对照: 末道报工能推出产出 (否则下面全是恒真)")
    void terminalReportProducesAnOutputLine() {
        List<ProductionSettlementRequest.OutputLine> out = derive(
                plan(FG), List.of(report(1, FG, "60", "kg"), report(2, FG, "75", "盒")));
        assertEquals(1, out.size(), "实际 " + out.size());
        assertEquals(FG, out.get(0).getProductTypeId());
        assertEquals("盒", out.get(0).getUnit(), "取的不是末道那一道");
        assertEquals(0, out.get(0).getQuantity().compareTo(new BigDecimal("75")));
    }

    @Test
    @DisplayName("🔴 只取末道 —— 中间道的产出不许混进来(那是半成品, 不是成品)")
    void onlyTheLastProcessCounts() {
        List<ProductionSettlementRequest.OutputLine> out = derive(
                plan(FG), List.of(report(1, FG, "60", "kg"), report(2, FG, "75", "盒")));
        assertTrue(out.stream().noneMatch(l -> "kg".equals(l.getUnit())),
                "把工序① 的半成品也算成末道产出了: " + out);
    }

    @Test
    @DisplayName("同一道多次报工按 (SKU,单位) 求和")
    void sameSkuAndUnitAreSummed() {
        List<ProductionSettlementRequest.OutputLine> out = derive(
                plan(FG), List.of(report(2, FG, "40", "盒"), report(2, FG, "35", "盒")));
        assertEquals(1, out.size());
        assertEquals(0, out.get(0).getQuantity().compareTo(new BigDecimal("75")));
    }

    @Test
    @DisplayName("🔴 阴性对照: 不跨单位相加 —— 同 SKU 出现两个单位要各自成行")
    void differentUnitsAreNotSummed() {
        List<ProductionSettlementRequest.OutputLine> out = derive(
                plan(FG), List.of(report(2, FG, "40", "盒"), report(2, FG, "5", "kg")));
        assertEquals(2, out.size(), "把 盒 和 kg 加到一起了: " + out);
    }

    @Test
    @DisplayName("报工没带 SKU 时回落计划的产品; 两者都没有就跳过(空 SKU 会让集合比对恒不相等)")
    void skuFallsBackToPlanAndBlankIsSkipped() {
        assertEquals(FG, derive(plan(FG), List.of(report(2, null, "75", "盒")))
                .get(0).getProductTypeId());
        assertTrue(derive(plan(null), List.of(report(2, null, "75", "盒"))).isEmpty(),
                "造出了一条 SKU 为空的产出行");
    }

    @Test
    @DisplayName("数量为 0 / 负 / 空的报工不产出行; 空输入不炸")
    void nonPositiveQuantitiesAreSkipped() {
        assertTrue(derive(plan(FG), List.of(report(2, FG, "0", "盒"))).isEmpty());
        assertTrue(derive(plan(FG), List.of(report(2, FG, null, "盒"))).isEmpty());
        assertTrue(derive(plan(FG), new ArrayList<>()).isEmpty());
    }

    @Test
    @DisplayName("老数据无 processOrder → 退化为全部都算末道 (与 deriveLastStepOutput 同口径)")
    void reportsWithoutProcessOrderDegradeToAll() {
        List<ProductionSettlementRequest.OutputLine> out = derive(
                plan(FG), List.of(report(null, FG, "40", "盒"), report(null, OTHER, "10", "盒")));
        assertEquals(2, out.size(), "实际 " + out);
    }

    @Test
    @DisplayName("🔴 接线闸: 那条腿的 prefill 必须真的带上 terminalOutputs —— helper 写对不等于接上了")
    void reportLegPrefillActuallyCarriesTerminalOutputs() throws Exception {
        String raw = Files.readString(SRC);
        // 先剥注释: 否则会数到讲这件事的那几段说明 (本仓形态 A⁗)
        String src = raw.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)^\\s*//.*$", "");
        int at = src.indexOf("deriveTerminalOutputsFromReports(plan, allReports)");
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
