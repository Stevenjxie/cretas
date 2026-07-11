package com.cretas.aims.logistics.entity.enums;

/**
 * 排线优化模式 — 生成计划时调度员二选一 (档1-B, 2026-07-11):
 * <ul>
 *   <li>{@link #TIME} 时间最快 — 地图 provider 按"速度优先"策略规划 (高德 strategy=0)。</li>
 *   <li>{@link #DISTANCE} 路程最短 — 按"距离最短"策略规划 (高德 strategy=2)，默认值。</li>
 * </ul>
 *
 * <p>持久化在 {@code logistics_plans.optimize_by} (VARCHAR)；{@code regeneratePlan}
 * 复用计划上已存的模式，保证重生成与初次生成口径一致。
 */
public enum RouteOptimizeMode {
    /** 时间最快 (高德 strategy=0 速度优先)。 */
    TIME,
    /** 路程最短 (高德 strategy=2 距离最短) — 默认。 */
    DISTANCE
}
