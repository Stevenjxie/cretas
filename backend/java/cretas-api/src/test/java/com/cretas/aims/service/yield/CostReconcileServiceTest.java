package com.cretas.aims.service.yield;

import com.cretas.aims.dto.yield.CostReconcileResult;
import com.cretas.aims.dto.yield.CostReconcileResult.ReconcileIssue;
import com.cretas.aims.dto.yield.StepYieldDTO;
import com.cretas.aims.entity.ProductWorkProcess;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 辅料对账引擎单测 (段2(B))。
 *
 * <p>核心验证 (审计②铁律): 标准侧 (standardYieldRate) vs 实际侧 (报工) 不同源 →
 * 实际==标准时 overFeed=0 (合法零, 非望远镜恒等0); 实际≠标准时信号非0。</p>
 */
class CostReconcileServiceTest {

    private final CostReconcileService svc = new CostReconcileService();

    private static StepYieldDTO step(int order, String pname,
                                     String in, String out, String inUnit, String outUnit) {
        StepYieldDTO s = new StepYieldDTO();
        s.setProcessOrder(order);
        s.setProcessName(pname);
        s.setTotalInput(in == null ? null : new BigDecimal(in));
        s.setTotalOutput(out == null ? null : new BigDecimal(out));
        s.setInputUnit(inUnit);
        s.setOutputUnit(outUnit);
        if (s.getTotalInput() != null && s.getTotalOutput() != null
                && inUnit != null && inUnit.equals(outUnit)
                && s.getTotalInput().compareTo(BigDecimal.ZERO) > 0) {
            s.setYieldRate(s.getTotalOutput().divide(s.getTotalInput(), 4, java.math.RoundingMode.HALF_UP));
        }
        return s;
    }

    private static ProductWorkProcess cfg(int order, String stdRate, String auxPrice, String basis) {
        return ProductWorkProcess.builder()
                .factoryId("F006").productTypeId("P1").workProcessId("wp" + order)
                .processOrder(order)
                .standardYieldRate(stdRate == null ? null : new BigDecimal(stdRate))
                .auxUnitPrice(auxPrice == null ? null : new BigDecimal(auxPrice))
                .auxBasis(basis)
                .build();
    }

    private static ReconcileIssue find(CostReconcileResult r, String code) {
        return r.getIssues().stream().filter(i -> code.equals(i.getCode())).findFirst().orElse(null);
    }

    // ── 1. 核心: 实际==标准 → overFeed 合法 0 (非恒等0陷阱) ──
    @Test
    void actualEqualsStandard_overFeedZero_noAlert() {
        List<StepYieldDTO> steps = List.of(
                step(0, "解冻", "100", "80", "kg", "kg"),
                step(1, "熟制", "80", "40", "kg", "kg"));
        List<ProductWorkProcess> cfgs = List.of(
                cfg(0, "0.8", null, null),
                cfg(1, "0.5", null, null));
        CostReconcileResult r = svc.reconcile(steps, cfgs, null, new BigDecimal("40"), null);

        assertTrue(r.isStandardComplete());
        assertEquals(0, new BigDecimal("100").compareTo(r.getStandardFirstInput()), "应投=40/0.4=100");
        assertEquals(0, new BigDecimal("100").compareTo(r.getActualFirstInput()));
        assertEquals(0, BigDecimal.ZERO.compareTo(r.getOverFeed()), "实际==标准 → 多投 0");
        assertFalse(r.isOverFeedAlert());
    }

    // ── 2. 核心: 实际多投 10% → 信号非0 + 预警 ──
    @Test
    void actualOverFeed_signalNonZero_alert() {
        List<StepYieldDTO> steps = List.of(
                step(0, "解冻", "110", "80", "kg", "kg"),   // 实际投 110, 标准应投 100
                step(1, "熟制", "80", "40", "kg", "kg"));
        List<ProductWorkProcess> cfgs = List.of(
                cfg(0, "0.8", null, null),
                cfg(1, "0.5", null, null));
        CostReconcileResult r = svc.reconcile(steps, cfgs, null, new BigDecimal("40"), null);

        assertEquals(0, new BigDecimal("100").compareTo(r.getStandardFirstInput()));
        assertEquals(0, new BigDecimal("10").compareTo(r.getOverFeed()), "多投=110-100=10");
        assertEquals(0, new BigDecimal("0.1000").compareTo(r.getOverFeedRate()), "多投率=10%");
        assertTrue(r.isOverFeedAlert(), "10% > 5% 阈值 → 预警");
        assertNotNull(find(r, "OVER_FEED"));
        assertEquals("WARN", find(r, "OVER_FEED").getSeverity());
    }

    // ── 3. 辅料 INPUT 基准: 标准/实际/多投 + 分摊 ──
    @Test
    void auxCost_inputBasis_standardActualOverAndPerUnit() {
        List<StepYieldDTO> steps = List.of(
                step(0, "解冻", "110", "80", "kg", "kg"),
                step(1, "熟制", "80", "40", "kg", "kg"));
        List<ProductWorkProcess> cfgs = List.of(
                cfg(0, "0.8", "2.0", "INPUT"),   // std in 100, act in 110
                cfg(1, "0.5", "1.0", "INPUT"));  // std in 80, act in 80
        CostReconcileResult r = svc.reconcile(steps, cfgs, null, new BigDecimal("40"), null);

        // std: 100*2 + 80*1 = 280; act: 110*2 + 80*1 = 300; over 20
        assertEquals(0, new BigDecimal("280.00").compareTo(r.getStandardAuxCostTotal()));
        assertEquals(0, new BigDecimal("300.00").compareTo(r.getActualAuxCostTotal()));
        assertEquals(0, new BigDecimal("20.00").compareTo(r.getAuxOverCostTotal()));
        // perUnit /40
        assertEquals(0, new BigDecimal("7.00").compareTo(r.getStandardAuxCostPerUnit()));
        assertEquals(0, new BigDecimal("7.50").compareTo(r.getActualAuxCostPerUnit()));
        assertEquals(0, new BigDecimal("0.50").compareTo(r.getAuxOverCostPerUnit()));
        // 20/280 = 7.14% > 5%
        assertTrue(r.isAuxAlert());
    }

    // ── 4. 辅料 OUTPUT 基准 (保水工序 output>input) ──
    @Test
    void auxCost_outputBasis() {
        List<StepYieldDTO> steps = List.of(
                step(0, "滚揉", "100", "105", "kg", "kg"));   // 保水 output>input
        List<ProductWorkProcess> cfgs = List.of(
                cfg(0, "1.05", "1.91", "OUTPUT"));
        CostReconcileResult r = svc.reconcile(steps, cfgs, null, new BigDecimal("105"), null);

        // std walk: stdFirstInput = lastOutput(105)/Π(1.05) = 100; stdOutput = 100*1.05 = 105
        // OUTPUT basis: std kg = 105, act kg = 105 → aux 105*1.91 = 200.55 both
        assertEquals(0, new BigDecimal("200.55").compareTo(r.getStandardAuxCostTotal()));
        assertEquals(0, new BigDecimal("200.55").compareTo(r.getActualAuxCostTotal()));
        assertEquals("OUTPUT", r.getSteps().get(0).getAuxBasis());
    }

    // ── 5. 工序无辅料单价 → 按 0 不崩 + NO_AUX_PRICE ──
    @Test
    void noAuxPrice_zeroNotCrash() {
        List<StepYieldDTO> steps = List.of(step(0, "解冻", "100", "80", "kg", "kg"));
        List<ProductWorkProcess> cfgs = List.of(cfg(0, "0.8", null, null));
        CostReconcileResult r = svc.reconcile(steps, cfgs, null, new BigDecimal("80"), null);

        assertNull(r.getStandardAuxCostTotal());
        assertNull(r.getActualAuxCostTotal());
        assertNotNull(find(r, "NO_AUX_PRICE"));
        assertFalse(r.getSteps().get(0).isHasAuxPrice());
    }

    // ── 6. 标准率不全 → 跳过应投对账, 不报假超产 ──
    @Test
    void incompleteStandardRate_suppressOverFeedAlert() {
        List<StepYieldDTO> steps = List.of(
                step(0, "解冻", "110", "80", "kg", "kg"),
                step(1, "熟制", "80", "40", "kg", "kg"));
        List<ProductWorkProcess> cfgs = List.of(
                cfg(0, "0.8", null, null));   // order 1 没配 → 链不全
        CostReconcileResult r = svc.reconcile(steps, cfgs, null, new BigDecimal("40"), null);

        assertFalse(r.isStandardComplete());
        assertNull(r.getStandardFirstInput(), "链不全 → 不算应投");
        assertFalse(r.isOverFeedAlert(), "链不全 → 永不报假超产");
        assertNotNull(find(r, "STANDARD_RATE_INCOMPLETE"));
    }

    // ── 7. 跨单位无 gramsPerUnit → 投料留空不误报 ──
    @Test
    void crossUnit_noGramsPerUnit_blankNoFalseAlarm() {
        List<StepYieldDTO> steps = List.of(
                step(0, "解冻", "100", "80", "kg", "kg"),
                step(1, "装盒", "80", "40", "kg", "盒"));   // 末道跨单位
        List<ProductWorkProcess> cfgs = List.of(
                cfg(0, "0.8", null, null),
                cfg(1, "0.5", null, null));
        CostReconcileResult r = svc.reconcile(steps, cfgs, null, new BigDecimal("40"), null);

        assertTrue(r.isStandardComplete(), "率都配了, 链完整");
        assertNull(r.getStandardFirstInput(), "跨单位无系数 → 应投留空");
        assertFalse(r.isOverFeedAlert());
        assertNotNull(find(r, "CROSS_UNIT_NO_FACTOR"));
    }

    // ── 8. 跨单位有 gramsPerUnit → 折算 ──
    @Test
    void crossUnit_withGramsPerUnit_converts() {
        List<StepYieldDTO> steps = List.of(
                step(0, "解冻", "100", "80", "kg", "kg"),
                step(1, "装盒", "80", "40", "kg", "盒"));   // 40 盒, 每盒 1000g = 1kg
        List<ProductWorkProcess> cfgs = List.of(
                cfg(0, "0.8", null, null),
                cfg(1, "0.5", null, null));
        // 40 盒 × 1000 / 1000 = 40 kg; Π=0.4; 应投=100
        CostReconcileResult r = svc.reconcile(steps, cfgs, new BigDecimal("1000"), new BigDecimal("40"), null);

        assertEquals(0, new BigDecimal("100").compareTo(r.getStandardFirstInput()));
        assertEquals(0, BigDecimal.ZERO.compareTo(r.getOverFeed()), "实际投100==应投100");
    }

    // ── 9. 自定义阈值 (工厂可配): 8% 阈值下 10% 多投仍报, 12% 阈值下不报 ──
    @Test
    void customThreshold() {
        List<StepYieldDTO> steps = List.of(
                step(0, "解冻", "110", "80", "kg", "kg"),
                step(1, "熟制", "80", "40", "kg", "kg"));
        List<ProductWorkProcess> cfgs = List.of(cfg(0, "0.8", null, null), cfg(1, "0.5", null, null));

        assertTrue(svc.reconcile(steps, cfgs, null, new BigDecimal("40"), new BigDecimal("0.08"))
                .isOverFeedAlert(), "10% > 8% → 报");
        assertFalse(svc.reconcile(steps, cfgs, null, new BigDecimal("40"), new BigDecimal("0.12"))
                .isOverFeedAlert(), "10% < 12% → 不报");
    }

    // ── 10. 空步骤 → NO_ACTUAL_STEPS 诚实空态 ──
    @Test
    void emptySteps_honestEmpty() {
        CostReconcileResult r = svc.reconcile(List.of(), List.of(), null, null, null);
        assertNotNull(find(r, "NO_ACTUAL_STEPS"));
        assertFalse(r.isStandardComplete());
        assertTrue(r.getSteps().isEmpty());
    }

    // ── 11. 份数缺失 → 仅总额, PORTION_COUNT_MISSING ──
    @Test
    void portionMissing_totalsOnly() {
        List<StepYieldDTO> steps = List.of(step(0, "解冻", "100", "80", "kg", "kg"));
        List<ProductWorkProcess> cfgs = List.of(cfg(0, "0.8", "2.0", "INPUT"));
        CostReconcileResult r = svc.reconcile(steps, cfgs, null, null, null);

        assertNotNull(r.getActualAuxCostTotal());
        assertNull(r.getActualAuxCostPerUnit());
        assertNotNull(find(r, "PORTION_COUNT_MISSING"));
    }

    // ── 12. 精度 HALF_UP 中间步 quantize (1/3 链) ──
    @Test
    void precision_halfUpQuantize() {
        // stdRate 0.3333, lastOutput 100 → 应投 = 100/0.3333 = 300.0300 (scale4 HALF_UP)
        List<StepYieldDTO> steps = List.of(step(0, "x", "300", "100", "kg", "kg"));
        List<ProductWorkProcess> cfgs = List.of(cfg(0, "0.3333", null, null));
        CostReconcileResult r = svc.reconcile(steps, cfgs, null, new BigDecimal("100"), null);

        // 100 / 0.3333 = 300.030003... → scale4 HALF_UP = 300.0300
        assertEquals(0, new BigDecimal("300.0300").compareTo(r.getStandardFirstInput()));
    }
}
