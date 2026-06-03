package com.cretas.aims.entity.restaurant.enums;

/**
 * 供应商送货单审核状态。
 *
 * <ul>
 *   <li>DRAFT — OCR/人工录入草稿, 可编辑行项</li>
 *   <li>CONFIRMED — 人工确认, 已写 gold 进价表 (agg_supplier_price)</li>
 *   <li>REJECTED — 拒绝 (Rule 3: 含标准原因码)</li>
 * </ul>
 *
 * @since 2026-06-03 (G7)
 */
public enum DeliveryNoteStatus {
    DRAFT,
    CONFIRMED,
    REJECTED
}
