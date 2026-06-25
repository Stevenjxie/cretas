package com.cretas.aims.service.yield;

import com.cretas.aims.dto.yield.CostReconcileResult;
import com.cretas.aims.dto.yield.CostReconcileResult.ReconcileIssue;
import com.cretas.aims.dto.yield.CostReconcileResult.StepReconcile;
import com.cretas.aims.dto.yield.StepYieldDTO;
import com.cretas.aims.entity.ProductWorkProcess;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 辅料标准单价双锚点投料-产出对账引擎 (段2(B))。
 *
 * <p><b>纯函数</b>: 不依赖 repository, 输入 = 已加载的实际报工步骤 + 标准配置 + 折算系数 + 份数,
 * 输出 = {@link CostReconcileResult}。全部对账逻辑可单测, wiring 由 controller/上层服务负责加载。</p>
 *
 * <p><b>审计②铁律</b>: 标准侧用 {@code standardYieldRate} (配方), 实际侧用 {@link StepYieldDTO}
 * 报工 — 两端不同信息源。{@code standardFirstInput = 实际末道产出(折首道单位) ÷ Π(标准率)},
 * 与 {@code actualFirstInput} 比 → 多投信号。绝不用实际率走标准链 (那会恒等 0 无信号)。</p>
 *
 * <p><b>精度</b> (mirror Java BigDecimal 语义, 中间步 quantize, 全程 HALF_UP):
 * kg scale 4, 金额 scale 2。</p>
 */
@Service
public class CostReconcileService {

    private static final int KG_SCALE = 4;
    private static final int MONEY_SCALE = 2;
    private static final BigDecimal THOUSAND = new BigDecimal("1000");
    /** 默认多投预警阈值 5% (镜像 SETTLEMENT_VARIANCE_THRESHOLD; 工厂可配则传入覆盖)。 */
    public static final BigDecimal DEFAULT_THRESHOLD = new BigDecimal("0.05");

    /**
     * 对账。
     *
     * @param actualSteps   逐工序实际报工 (来自 YieldCalculationService.calculateSteps; 任意顺序, 内部按 processOrder 排)
     * @param configs       (产品×工序) 配置 (提供 standardYieldRate / auxUnitPrice / auxBasis); 按 processOrder 匹配
     * @param gramsPerUnit  ProductType.gramsPerUnit, 跨单位 份/盒→kg 折算 (可空 → 跨单位时留空不误报)
     * @param portionCount  份数 N (可空 → 仅出总额, 不分摊到每份)
     * @param threshold     多投预警阈值 (小数; null → {@link #DEFAULT_THRESHOLD})
     */
    public CostReconcileResult reconcile(List<StepYieldDTO> actualSteps,
                                         List<ProductWorkProcess> configs,
                                         BigDecimal gramsPerUnit,
                                         BigDecimal portionCount,
                                         BigDecimal threshold) {
        BigDecimal thr = threshold != null ? threshold : DEFAULT_THRESHOLD;
        List<ReconcileIssue> issues = new ArrayList<>();

        if (actualSteps == null || actualSteps.isEmpty()) {
            issues.add(info("NO_ACTUAL_STEPS",
                    "该批次暂无逐道报工记录, 无法对账; 请先完成逐道报工后再核算辅料成本。"));
            return CostReconcileResult.builder()
                    .threshold(thr).standardComplete(false).linear(true)
                    .portionCount(portionCount)
                    .steps(new ArrayList<>()).issues(issues)
                    .build();
        }

        // 防御: 按 processOrder 升序 (null order 视为 0, 排前)
        List<StepYieldDTO> steps = new ArrayList<>(actualSteps);
        steps.sort(Comparator.comparingInt(s -> s.getProcessOrder() == null ? 0 : s.getProcessOrder()));

        Map<Integer, ProductWorkProcess> cfgByOrder = new HashMap<>();
        if (configs != null) {
            for (ProductWorkProcess c : configs) {
                if (c != null && c.getProcessOrder() != null) {
                    cfgByOrder.put(c.getProcessOrder(), c);
                }
            }
        }

        StepYieldDTO first = steps.get(0);
        StepYieldDTO last = steps.get(steps.size() - 1);
        String firstInputUnit = first.getInputUnit();
        String lastOutputUnit = last.getOutputUnit();
        BigDecimal actualFirstInput = first.getTotalInput();
        BigDecimal actualLastOutput = last.getTotalOutput();

        // ── 标准链完整性: 每道实际工序都必须有 standardYieldRate (>0), 否则 Π 偏 → 不报假超产 ──
        boolean standardComplete = true;
        BigDecimal product = BigDecimal.ONE;   // Π(standardYieldRate); multiply 精确, 不中途 round
        for (StepYieldDTO s : steps) {
            ProductWorkProcess c = cfgByOrder.get(orderOf(s));
            BigDecimal r = c != null ? c.getStandardYieldRate() : null;
            if (r == null || r.compareTo(BigDecimal.ZERO) <= 0) {
                standardComplete = false;
            } else {
                product = product.multiply(r);
            }
        }
        if (!standardComplete) {
            issues.add(info("STANDARD_RATE_INCOMPLETE",
                    "部分工序未配置标准出成率, 已跳过「应投/多投」投料对账 (不报假超产); "
                            + "请到 产品-工序配置 补全各道标准出成率以启用投料预警。"));
        }

        // ── 实际末道产出折算到首道单位 (份/盒 → kg) ──
        BigDecimal actualLastOutputInFirstUnit = null;
        boolean sameUnit = firstInputUnit != null && firstInputUnit.equals(lastOutputUnit);
        if (actualLastOutput != null && actualLastOutput.compareTo(BigDecimal.ZERO) > 0) {
            if (sameUnit) {
                actualLastOutputInFirstUnit = actualLastOutput;
            } else if (gramsPerUnit != null && gramsPerUnit.compareTo(BigDecimal.ZERO) > 0) {
                actualLastOutputInFirstUnit = actualLastOutput.multiply(gramsPerUnit)
                        .divide(THOUSAND, KG_SCALE, RoundingMode.HALF_UP);
            } else {
                issues.add(info("CROSS_UNIT_NO_FACTOR",
                        "末道产出单位(" + nz(lastOutputUnit) + ")与原料单位(" + nz(firstInputUnit)
                                + ")不同且未配置每份克重, 无法折算应投; 投料对账留空。请在产品资料配置 gramsPerUnit。"));
            }
        }

        // ── 标准应投 (应投 kg) = 实际末道产出(折首道单位) ÷ Π(标准率) ──
        BigDecimal standardFirstInput = null;
        BigDecimal overFeed = null;
        BigDecimal overFeedRate = null;
        boolean overFeedAlert = false;
        if (standardComplete && actualLastOutputInFirstUnit != null
                && product.compareTo(BigDecimal.ZERO) > 0) {
            standardFirstInput = actualLastOutputInFirstUnit.divide(product, KG_SCALE, RoundingMode.HALF_UP);
            if (actualFirstInput != null && standardFirstInput.compareTo(BigDecimal.ZERO) > 0) {
                overFeed = actualFirstInput.subtract(standardFirstInput);
                overFeedRate = overFeed.divide(standardFirstInput, KG_SCALE, RoundingMode.HALF_UP);
                overFeedAlert = overFeedRate.compareTo(thr) > 0;   // 正向多投超阈值
            }
        }

        // ── 逐工序标准 kg (从 standardFirstInput 正向走链); 仅 standardComplete 有效 ──
        Map<Integer, BigDecimal[]> stdKgByOrder = new HashMap<>();   // [input, output]
        if (standardComplete && standardFirstInput != null) {
            BigDecimal walkInput = standardFirstInput;
            for (StepYieldDTO s : steps) {
                ProductWorkProcess c = cfgByOrder.get(orderOf(s));
                BigDecimal r = c.getStandardYieldRate();   // standardComplete → 必非 null
                BigDecimal walkOutput = walkInput.multiply(r).setScale(KG_SCALE, RoundingMode.HALF_UP);
                stdKgByOrder.put(orderOf(s),
                        new BigDecimal[]{walkInput.setScale(KG_SCALE, RoundingMode.HALF_UP), walkOutput});
                walkInput = walkOutput;
            }
        }

        // ── 逐工序对账 + 辅料总额 (null-safe Σ) ──
        List<StepReconcile> stepResults = new ArrayList<>();
        BigDecimal stdAuxTotal = null;
        BigDecimal actAuxTotal = null;
        for (StepYieldDTO s : steps) {
            ProductWorkProcess c = cfgByOrder.get(orderOf(s));
            BigDecimal rate = c != null ? c.getStandardYieldRate() : null;
            BigDecimal price = c != null ? c.getAuxUnitPrice() : null;
            String basisRaw = c != null ? c.getAuxBasis() : null;
            boolean output = "OUTPUT".equalsIgnoreCase(basisRaw);   // 默认 (含 null/INPUT) 取投入侧
            boolean hasPrice = price != null && price.compareTo(BigDecimal.ZERO) > 0;

            // 实际 kg 折成 mass 单位 (与标准 kg 链同单位): 末道 盒/份 产出按 gramsPerUnit 折 kg;
            // 跨单位且无折算系数 → null (与标准侧 null 一致, 不产生假多投信号)。
            BigDecimal actKg = output
                    ? foldToMass(s.getTotalOutput(), s.getOutputUnit(), firstInputUnit, gramsPerUnit)
                    : foldToMass(s.getTotalInput(), s.getInputUnit(), firstInputUnit, gramsPerUnit);
            BigDecimal[] sk = stdKgByOrder.get(orderOf(s));
            BigDecimal stdKgStep = sk == null ? null : (output ? sk[1] : sk[0]);

            BigDecimal stepStdAux = null;
            BigDecimal stepActAux = null;
            if (hasPrice) {
                if (actKg != null) {
                    stepActAux = actKg.multiply(price).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                }
                if (stdKgStep != null) {
                    stepStdAux = stdKgStep.multiply(price).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                }
            }
            if (stepActAux != null) {
                actAuxTotal = (actAuxTotal == null ? BigDecimal.ZERO : actAuxTotal).add(stepActAux);
            }
            if (stepStdAux != null) {
                stdAuxTotal = (stdAuxTotal == null ? BigDecimal.ZERO : stdAuxTotal).add(stepStdAux);
            }
            BigDecimal stepOver = (stepStdAux != null && stepActAux != null)
                    ? stepActAux.subtract(stepStdAux) : null;

            stepResults.add(StepReconcile.builder()
                    .processOrder(s.getProcessOrder())
                    .processName(s.getProcessName())
                    .standardYieldRate(rate)
                    .actualYieldRate(s.getYieldRate())
                    .auxBasis(basisRaw)
                    .auxUnitPrice(price)
                    .standardKg(stdKgStep)
                    .actualKg(actKg)
                    .standardAuxCost(stepStdAux)
                    .actualAuxCost(stepActAux)
                    .auxOverCost(stepOver)
                    .configured(rate != null && rate.compareTo(BigDecimal.ZERO) > 0)
                    .hasAuxPrice(hasPrice)
                    .build());
        }

        // ── 辅料多投差异 + 率 + 预警 ──
        BigDecimal auxOverTotal = (stdAuxTotal != null && actAuxTotal != null)
                ? actAuxTotal.subtract(stdAuxTotal) : null;
        BigDecimal auxOverRate = null;
        boolean auxAlert = false;
        if (auxOverTotal != null && stdAuxTotal != null && stdAuxTotal.compareTo(BigDecimal.ZERO) > 0) {
            auxOverRate = auxOverTotal.divide(stdAuxTotal, KG_SCALE, RoundingMode.HALF_UP);
            auxAlert = auxOverRate.compareTo(thr) > 0;
        }

        // ── 分摊到每份 (÷ N) ──
        BigDecimal stdPer = null;
        BigDecimal actPer = null;
        BigDecimal overPer = null;
        if (portionCount != null && portionCount.compareTo(BigDecimal.ZERO) > 0) {
            if (stdAuxTotal != null) {
                stdPer = stdAuxTotal.divide(portionCount, MONEY_SCALE, RoundingMode.HALF_UP);
            }
            if (actAuxTotal != null) {
                actPer = actAuxTotal.divide(portionCount, MONEY_SCALE, RoundingMode.HALF_UP);
            }
            if (auxOverTotal != null) {
                overPer = auxOverTotal.divide(portionCount, MONEY_SCALE, RoundingMode.HALF_UP);
            }
        } else {
            issues.add(info("PORTION_COUNT_MISSING",
                    "份数缺失, 仅显示辅料总额, 无法分摊到每份; 请确认末道产出数量。"));
        }

        boolean anyPrice = stepResults.stream().anyMatch(StepReconcile::isHasAuxPrice);
        if (!anyPrice) {
            issues.add(info("NO_AUX_PRICE",
                    "尚未配置任何工序辅料单价, 辅料成本按 0 计; 如需核算辅料, 请到 产品-工序配置 录入辅料单价(元/kg)。"));
        }
        if (overFeedAlert) {
            issues.add(warn("OVER_FEED",
                    "实际投料(" + strip(actualFirstInput) + nz(firstInputUnit) + ")超出标准应投("
                            + strip(standardFirstInput) + nz(firstInputUnit) + ") "
                            + pct(overFeedRate) + ", 超过阈值 " + pct(thr)
                            + "; 请核对投料/产出/出成率是否异常 (多投=浪费或出成偏低)。"));
        }
        if (auxAlert && !overFeedAlert) {
            issues.add(warn("AUX_OVER",
                    "实际辅料成本较标准高 " + pct(auxOverRate) + ", 超过阈值 " + pct(thr)
                            + "; 请核对投料量与辅料单价。"));
        }

        return CostReconcileResult.builder()
                .standardFirstInput(standardFirstInput)
                .actualFirstInput(actualFirstInput)
                .firstInputUnit(firstInputUnit)
                .overFeed(overFeed)
                .overFeedRate(overFeedRate)
                .overFeedAlert(overFeedAlert)
                .portionCount(portionCount)
                .standardAuxCostTotal(stdAuxTotal)
                .actualAuxCostTotal(actAuxTotal)
                .auxOverCostTotal(auxOverTotal)
                .auxOverRate(auxOverRate)
                .auxAlert(auxAlert)
                .standardAuxCostPerUnit(stdPer)
                .actualAuxCostPerUnit(actPer)
                .auxOverCostPerUnit(overPer)
                .threshold(thr)
                .standardComplete(standardComplete)
                .linear(true)
                .steps(stepResults)
                .issues(issues)
                .build();
    }

    private static int orderOf(StepYieldDTO s) {
        return s.getProcessOrder() == null ? 0 : s.getProcessOrder();
    }

    /**
     * 把数量折成 mass 基准单位 (与标准 kg 链一致)。
     * <p>同单位/单位未知 → 原值; 跨单位且有 gramsPerUnit → ×gpu/1000 折 kg; 跨单位无系数 → null
     * (诚实留空, 与标准侧 null 一致, 不产生假对账)。</p>
     */
    private static BigDecimal foldToMass(BigDecimal qty, String unit, String baseUnit, BigDecimal gpu) {
        if (qty == null) {
            return null;
        }
        if (baseUnit == null || unit == null || unit.equals(baseUnit)) {
            return qty;
        }
        if (gpu != null && gpu.compareTo(BigDecimal.ZERO) > 0) {
            return qty.multiply(gpu).divide(THOUSAND, KG_SCALE, RoundingMode.HALF_UP);
        }
        return null;
    }

    private static ReconcileIssue info(String code, String message) {
        return ReconcileIssue.builder().code(code).message(message).severity("INFO").build();
    }

    private static ReconcileIssue warn(String code, String message) {
        return ReconcileIssue.builder().code(code).message(message).severity("WARN").build();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 去尾零展示 (12.0000 → 12; 12.5000 → 12.5)。 */
    private static String strip(BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().toPlainString();
    }

    /** 小数率 → 百分比文案 (0.0833 → 8.33%)。 */
    private static String pct(BigDecimal rate) {
        if (rate == null) {
            return "";
        }
        return rate.multiply(new BigDecimal("100"))
                .setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "%";
    }
}
