package com.cretas.aims.entity.restaurant.enums;

/**
 * 供应商送货单来源类型。
 *
 * <ul>
 *   <li>OCR — DashScope Vision 识别送货单照片自动解析行项</li>
 *   <li>MANUAL — OCR 失败/低置信时用户手工录入 (决策 D2)</li>
 * </ul>
 *
 * @since 2026-06-03 (G7)
 */
public enum DeliveryNoteSourceType {
    OCR,
    MANUAL
}
