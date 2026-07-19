package com.cretas.aims.dto.processentry;

import com.cretas.aims.entity.MaterialBatch;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 已解析的上游消耗边 (SP-F Task 1.3).
 *
 * <p>一条已经被 <b>调用方</b>解析好的上游消耗边 —— resolution(混锅来源批次的查找)
 * 由 caller 完成, {@code materializeBatch} 只负责 WRITE。
 *
 * <ul>
 *   <li>{@code recordChain} 的 caller: 经 in-memory {@code wipMbIdByKey} map 解析上游 WIP 批次。</li>
 *   <li>新的逐行 caller (later task): 经 PERSISTED 批次号解析上游(不同策略)。</li>
 * </ul>
 *
 * <p>{@code sourceBatch} 是已 factory-scoped-fetched 的 RAW 原料批 或 上游 WIP 批 ——
 * 成本边 totalCost = feedQuantityKg × sourceBatch.unitPrice。
 */
@Data
public class ResolvedEdge {
    /** 已 factory-scoped-fetched 的来源批次 (RAW 原料批 或 上游 WIP 批)。 */
    private MaterialBatch sourceBatch;
    /** 投料量 (kg)。 */
    private BigDecimal feedQuantityKg;
    /** "RAW_MATERIAL" | "SEMI_FINISHED" —— 写入 MaterialConsumption.sourceType。 */
    private String sourceType;
    /** Optional source price resolved from the owning production batch. */
    private BigDecimal resolvedUnitPrice;

    public ResolvedEdge(MaterialBatch sourceBatch, BigDecimal feedQuantityKg, String sourceType) {
        this(sourceBatch, feedQuantityKg, sourceType, null);
    }

    public ResolvedEdge(MaterialBatch sourceBatch, BigDecimal feedQuantityKg, String sourceType,
                        BigDecimal resolvedUnitPrice) {
        this.sourceBatch = sourceBatch;
        this.feedQuantityKg = feedQuantityKg;
        this.sourceType = sourceType;
        this.resolvedUnitPrice = resolvedUnitPrice;
    }
}
