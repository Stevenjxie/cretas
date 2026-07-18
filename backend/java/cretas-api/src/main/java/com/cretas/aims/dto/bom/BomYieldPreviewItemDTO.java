package com.cretas.aims.dto.bom;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * BOM 出成率批量预览单行 DTO.
 *
 * <p>POST /api/mobile/{factoryId}/bom/yield-estimate/recalculate-preview 的列表元素.
 * 纯只读; 不写任何数据.
 *
 * <p>status 含义:
 * <ul>
 *   <li>UPDATABLE — suggestedYieldRate != null 且与 currentYieldRate 不同</li>
 *   <li>INSUFFICIENT_SAMPLES — 有效样本 < 3</li>
 *   <li>SKIP — 无变化或该产品无 RAW 主料行</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BomYieldPreviewItemDTO {

    /** 产品类型 ID */
    private String productTypeId;

    /** 产品名称 */
    private String productName;

    /** 当前生效 BOM 配方 ID。 */
    private String recipeId;

    /** 原料名称 */
    private String materialName;

    /**
     * 当前 bom_recipes.overall_yield_rate (null 表示样本尚不足).
     *
     * <p>前端应将此值在调用 recalculate-apply 时回传为
     * {@link BomYieldApplyRequest#getExpectedCurrentYieldRate()},
     * 以启用 M10 乐观并发保护 (stale 检测).
     */
    private BigDecimal currentYieldRate;

    /**
     * 建议出成率 (P50 中位数, ×100, 2dp, ≤100).
     * null 时表示样本不足.
     */
    private BigDecimal suggestedYieldRate;

    /** 有效样本数量 */
    private int sampleCount;

    /** UPDATABLE / INSUFFICIENT_SAMPLES / SKIP */
    private String status;
}
