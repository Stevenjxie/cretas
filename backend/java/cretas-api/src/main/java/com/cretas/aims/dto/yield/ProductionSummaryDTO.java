package com.cretas.aims.dto.yield;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class ProductionSummaryDTO {
    private String planId;
    private String planNumber;
    private String productTypeId;
    private String productName;
    /** 本计划自身原料投入重(kg) = Σ 首道行 inputQuantity。 */
    private BigDecimal totalRawInput;
    /**
     * 复用半成品(SFI)批次接上的前段原料投入重(kg) — ①d。
     * = Σ(被复用外部 SFI 批次的前段原料 × 领用比例)。无复用或全部前段缺失 → 0。
     */
    private BigDecimal reusedFrontRawInput;
    /**
     * 真实出成率分母(kg) = totalRawInput + reusedFrontRawInput。
     * realYieldRate 即以此为分母, 便于前端把"前面的数据接上"可见化。
     */
    private BigDecimal yieldRawDenominator;
    private BigDecimal totalFinishedOutput;
    /** 成品总重(kg) = Σ 末道(COMPLETED)行 productWeight; 未录时 null */
    private BigDecimal totalFinishedWeight;
    private BigDecimal remainingSemiFinished;
    private BigDecimal realYieldRate;
    /**
     * 提示文字: 成品重量未录入 (realYieldRate 为 null) 时填充; 或复用批次前段数据缺失
     * (出成率分母未含其前段, 诚实告知不显错数) 时追加。
     */
    private String yieldNote;
    private BigDecimal totalCost;
    private boolean priceMasked;
    private List<BatchLine> batches;
    /** 复用半成品血缘: 每个被复用的外部 SFI 批次的来源/领用/前段接续明细 (①d 血缘可见)。 */
    private List<ReusedSemiLineage> reusedSemiLineages;

    @Data
    @Builder
    public static class BatchLine {
        private String batchNumber;
        private Integer processOrder;
        private String processName;
        private BigDecimal produced;
        private BigDecimal remaining;
        private String status;
        private BigDecimal cumulativeYieldRate;
    }

    /**
     * 复用半成品血缘明细 (①d) — 一条 = 本计划复用的一个外部 SFI 批次。
     *
     * <p>「前面的数据是有的」可见化: 展示被复用批次的来源批号、领用量、该批次自身产出量,
     * 以及接续进本计划出成率分母的前段原料重 (可空——缺 provenance 时诚实 null + note)。
     */
    @Data
    @Builder
    public static class ReusedSemiLineage {
        /** 被复用的外部半成品批次号 (SFI intermediateBatchNo)。 */
        private String sourceBatchNumber;
        /** 本计划从该批次领用的量(kg)。 */
        private BigDecimal drawnQuantity;
        /** 该 SFI 批次自身的总产出量(kg) — 用于按领用比例折算前段。 */
        private BigDecimal sourceProducedQuantity;
        /** 领用比例 = drawnQuantity / sourceProducedQuantity (%)。 */
        private BigDecimal drawnRatio;
        /** 该批次生产它的来源计划 id (前段所在计划; 无法解析时 null)。 */
        private String sourcePlanId;
        /**
         * 接续进本计划出成率分母的前段原料重(kg) = 该批次前段原料 × 领用比例。
         * null = 前段 provenance 缺失 (未接入分母, 见 note); 诚实不伪造。
         */
        private BigDecimal frontRawInput;
        /** 该批次前段原料是否已计入本计划出成率分母。 */
        private boolean frontRawIncluded;
        /** frontRawIncluded=false 时的原因说明 (前段数据缺失/来源计划不可解析等)。 */
        private String note;
    }
}
