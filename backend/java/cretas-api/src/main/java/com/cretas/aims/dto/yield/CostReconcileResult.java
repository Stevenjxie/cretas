package com.cretas.aims.dto.yield;

import com.cretas.aims.security.PriceSensitive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 辅料标准单价双锚点投料-产出对账结果 (段2(B), 抓多投/误差/浪费)。
 *
 * <p><b>核心信号</b>: 投料端用<b>标准配方率</b> (standardYieldRate) 反推「应投」, 出成端用
 * <b>实际报工</b> → 两者之差 = 多投/误差。审计②铁律: 对账两端必须<b>一端标准、一端实际</b>,
 * 禁两端同源 (否则望远镜收敛恒等 0, 无信号)。本 DTO 的 standard* 全来自 standardYieldRate 链,
 * actual* 全来自 {@link StepYieldDTO} 报工 — 结构上不可能同源。</p>
 *
 * <p><b>诚实留空</b>: 标准率不全 → 跳过应投对账 ({@code standardComplete=false}, 不报假超产);
 * 跨单位无折算系数 → 投料对账留空; 工序无辅料单价 → 该工序辅料按 0 不崩。所有"留空"在
 * {@code issues} 标 INFO/WARN, 绝不臆造。</p>
 *
 * <p><b>只读</b>: 本对账是核算页展示口径, 不触发任何库存扣减。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CostReconcileResult {

    // ── 投料-产出对账 (原料, kg; 抓多投) ──────────────────────────────────────────
    /** 标准应投 = 实际末道产出(折首道单位) ÷ Π(标准出成率); 标准率不全/跨单位无系数 → null (不臆造) */
    private BigDecimal standardFirstInput;
    /** 实际首道投料 (报工) */
    private BigDecimal actualFirstInput;
    /** 首道投入单位 (kg 等) */
    private String firstInputUnit;
    /** 多投/误差 = 实际投料 − 标准应投 (>0 多投); 任一端 null → null */
    private BigDecimal overFeed;
    /** 多投率 = overFeed ÷ standardFirstInput (小数, 0.05=5%) */
    private BigDecimal overFeedRate;
    /** 多投预警: overFeedRate > 阈值 且 standardComplete (标准率不全时永不报假警) */
    @Builder.Default
    private boolean overFeedAlert = false;

    // ── 辅料成本 (标准单价分摊, 元) ────────────────────────────────────────────────
    /** 份数 N (末道产出数量); null → 无法分摊到每份 */
    private BigDecimal portionCount;
    /** 标准辅料总成本 = Σ 各工序标准 kg(按 auxBasis) × auxUnitPrice; 标准率不全 → null */
    @PriceSensitive
    private BigDecimal standardAuxCostTotal;
    /** 实际辅料总成本 = Σ 各工序实际 kg(按 auxBasis) × auxUnitPrice */
    @PriceSensitive
    private BigDecimal actualAuxCostTotal;
    /** 多投辅料差异 = 实际 − 标准 (原料多投 → 辅料按固定比例同步放大); 任一端 null → null */
    @PriceSensitive
    private BigDecimal auxOverCostTotal;
    /** 辅料多投率 = auxOverCostTotal ÷ standardAuxCostTotal */
    private BigDecimal auxOverRate;
    /** 辅料多投预警: auxOverRate > 阈值 */
    @Builder.Default
    private boolean auxAlert = false;
    /** 标准辅料 / 份 */
    @PriceSensitive
    private BigDecimal standardAuxCostPerUnit;
    /** 实际辅料 / 份 */
    @PriceSensitive
    private BigDecimal actualAuxCostPerUnit;
    /** 多投辅料 / 份 */
    @PriceSensitive
    private BigDecimal auxOverCostPerUnit;

    // ── 元信息 ────────────────────────────────────────────────────────────────────
    /** 预警阈值 (小数, 默认 0.05; 工厂可配传入) */
    private BigDecimal threshold;
    /** 标准链是否完整 (每道工序都配了 standardYieldRate); false → 应投/标准辅料对账跳过 */
    @Builder.Default
    private boolean standardComplete = false;
    /** 是否线性链 (本期只支持线性; 混批/diamond defer, 见 spec §5.5) */
    @Builder.Default
    private boolean linear = true;

    /** 逐工序对账明细 */
    @Builder.Default
    private List<StepReconcile> steps = new ArrayList<>();
    /** 诚实留空/边界提示 (INFO/WARN; 非阻塞, 核算页展示) */
    @Builder.Default
    private List<ReconcileIssue> issues = new ArrayList<>();

    /** 逐工序对账明细 (标准 kg vs 实际 kg, 标准辅料 vs 实际辅料)。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StepReconcile {
        private Integer processOrder;
        private String processName;
        /** 标准出成率 (配方率); null = 该工序未配 */
        private BigDecimal standardYieldRate;
        /** 实际出成率 (报工; 单位不可比时 null) */
        private BigDecimal actualYieldRate;
        /** 元/kg 乘哪侧 INPUT|OUTPUT (null = 默认 INPUT) */
        private String auxBasis;
        /** 辅料单价 元/kg */
        @PriceSensitive
        private BigDecimal auxUnitPrice;
        /** 标准 kg (按 auxBasis 取标准投入侧或产出侧); 标准链不全 → null */
        private BigDecimal standardKg;
        /** 实际 kg (按 auxBasis 取报工投入侧或产出侧) */
        private BigDecimal actualKg;
        /** 本道标准辅料 = standardKg × auxUnitPrice */
        @PriceSensitive
        private BigDecimal standardAuxCost;
        /** 本道实际辅料 = actualKg × auxUnitPrice */
        @PriceSensitive
        private BigDecimal actualAuxCost;
        /** 本道多投辅料 = 实际 − 标准 */
        @PriceSensitive
        private BigDecimal auxOverCost;
        /** 该工序是否配了标准出成率 */
        @Builder.Default
        private boolean configured = false;
        /** 该工序是否配了辅料单价 (>0) */
        @Builder.Default
        private boolean hasAuxPrice = false;
    }

    /** 对账边界提示 (诚实留空原因)。severity: INFO (提示) / WARN (多投预警)。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReconcileIssue {
        /** 机器可读码 (前端高亮/跳转用) */
        private String code;
        /** 人类可读说明 (含 next-action, 防呆 4 位一体) */
        private String message;
        /** INFO | WARN */
        @Builder.Default
        private String severity = "INFO";
    }
}
