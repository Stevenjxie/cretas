package com.cretas.aims.entity.enums;

/**
 * 生产业态枚举
 *
 * <ul>
 *   <li>BY_ORDER — 销售订单生产 (默认): 对应具体销售订单的计划生产。</li>
 *   <li>BY_STOCK — 库存(永续)生产: 不绑定销售订单, 面向库存补货的连续生产。</li>
 * </ul>
 *
 * @since 2026-06-30
 */
public enum ProductionMode {
    BY_ORDER,
    BY_STOCK;

    /**
     * 从字符串解析枚举，null/blank/非法值均返回 BY_ORDER（保持向后兼容）。
     */
    public static ProductionMode fromString(String s) {
        if (s == null || s.isBlank()) {
            return BY_ORDER;
        }
        try {
            return ProductionMode.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return BY_ORDER;
        }
    }
}
