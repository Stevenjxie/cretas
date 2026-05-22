package com.cretas.aims.entity.canvas;

/**
 * Canvas-Thresholds 阈值分类 (Phase A P0-1).
 *
 * <p>对应 source-of-truth doc 中 6 个大类:
 * <ul>
 *   <li>{@link #INVENTORY} — 库存健康 (周转 / 临期 / 损耗 / 库龄)</li>
 *   <li>{@link #IOT}       — IoT 冷链 / 环境 (温度 / 湿度)</li>
 *   <li>{@link #CREDIT}    — 客户信用 (预警利用率)</li>
 *   <li>{@link #BOM}       — BOM 递归 (max depth)</li>
 *   <li>{@link #SHORTAGE}  — 缺料分析 (默认 lead time)</li>
 *   <li>{@link #AI}        — AI 复杂度路由 (mode threshold + ambiguous zone)</li>
 * </ul>
 *
 * @since Canvas Phase A (2026-05-21)
 */
public enum ThresholdCategory {
    INVENTORY,
    IOT,
    CREDIT,
    BOM,
    SHORTAGE,
    AI
}
