package com.cretas.aims.dto.bom;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * BOM 出成率评估结果 DTO
 *
 * <p>GET /api/mobile/{factoryId}/bom/yield-estimate 的响应体。
 * 基于最近 N=10 个已完成批次的累计出成率, 推荐合理的出成率 (P50/中位数).
 *
 * <p>source 含义:
 * <ul>
 *   <li>BATCH_REPORTING — suggestedYieldRate 来自 ≥3 个有效批次的中位数</li>
 *   <li>STANDARD_WEIGHT_ONLY — suggestedStandardQuantity 来自 ProductType.gramsPerUnit,
 *       但 suggestedYieldRate 不足 3 个样本</li>
 *   <li>NONE — 两者均无法提供建议</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BomYieldEstimateDTO {

    /** 产品类型 ID */
    private String productTypeId;

    /** 物料分类 (当前仅 RAW) */
    private String materialCategory;

    /**
     * 建议成品含量 (克/单位).
     * = ProductType.gramsPerUnit; null 时表示尚未配置标准克重.
     */
    private BigDecimal suggestedStandardQuantity;

    /**
     * 建议出成率 (0–100 的百分比, 2 位小数).
     * = 最近 N=10 个已完成批次 cumulativeYieldRate ×100 的 P50 中位数, 上限 100.
     * 有效样本 < 3 时为 null.
     */
    private BigDecimal suggestedYieldRate;

    /** 有效样本数量 */
    private int sampleCount;

    /** 样本出成率最小值 (×100, 2dp); 无有效样本时 null */
    private BigDecimal yieldMin;

    /** 样本出成率最大值 (×100, 2dp); 无有效样本时 null */
    private BigDecimal yieldMax;

    /**
     * 建议来源.
     * BATCH_REPORTING / STANDARD_WEIGHT_ONLY / NONE
     */
    private String source;

    /**
     * 原因码, 解释为何 suggestedYieldRate 为 null.
     * null — 建议正常; INSUFFICIENT_SAMPLES — 样本 <3; NO_GRAMS_PER_UNIT — 缺克重配置.
     */
    private String reason;

    /** 用户行动提示 (防呆 Rule 5) */
    private String actionHint;
}
