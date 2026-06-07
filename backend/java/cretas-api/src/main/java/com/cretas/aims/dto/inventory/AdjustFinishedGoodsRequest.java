package com.cretas.aims.dto.inventory;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * T126 Phase 1 — POST /finished-goods/{id}/adjust 调整数量请求 DTO
 *
 * <p>Agent B 类型化契约（前端 interface）：
 * <pre>
 * interface AdjustFinishedGoodsRequest {
 *   adjustmentQuantity: number;           // 正数=增加, 负数=减少（不得使可用量为负）
 *   reason: string;
 *   referenceType: AdjustmentReferenceType;
 * }
 *
 * type AdjustmentReferenceType =
 *   | 'OPENING_CORRECTION'
 *   | 'STOCKTAKE'
 *   | 'SCRAP'
 *   | 'OTHER';
 * </pre>
 */
public record AdjustFinishedGoodsRequest(
        @NotNull BigDecimal adjustmentQuantity,
        String reason,
        @NotNull ReferenceType referenceType
) {

    /** 调整来源类型 */
    public enum ReferenceType {
        OPENING_CORRECTION,
        STOCKTAKE,
        SCRAP,
        OTHER
    }
}
