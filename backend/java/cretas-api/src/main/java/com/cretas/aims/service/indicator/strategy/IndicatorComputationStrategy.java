package com.cretas.aims.service.indicator.strategy;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 真业务 indicator 计算策略 — Sprint 12 Phase B 新增.
 *
 * <p>替换 Sprint 11 V_23_11 mirror band-aid: 每个 indicator code 实现一个 SQL aggregate
 * 真接 sales_orders / production_batches / quality_inspections 等业务表, 而不是 mirror
 * F999_MOCK 静态值. 计算后 {@code IndicatorVersion.computeSource} 标记 "REAL_BUSINESS:&lt;code&gt;"
 * (per .claude/rules/python-java-port.md Rule 21 mock 严格隔离).
 *
 * <p>路由方式: {@code indicator_computations.compute_type = 'JPA_AGGREGATE'} +
 * {@code compute_source} 为空 (Java side 不需要), Registry 按 {@link #getIndicatorCode()} 选实现.
 * IndicatorQueryServiceImpl.fetchValue 看到 JPA_AGGREGATE 时调 strategy 而不是 PythonSmartBIClient.
 *
 * <p>性能: 走 CACHED compute_strategy + cache_ttl_seconds (默认 900 = 15min), Dashboard
 * 多次 load 不会重复跑 aggregate. Phase B 后期加 @Scheduled 每日 02:00 重算保 cache 暖.
 *
 * @author Cretas Team
 * @since 2026-05-29 (Sprint 12 Phase B)
 */
public interface IndicatorComputationStrategy {

    /**
     * 该策略服务的 indicator code, e.g. {@code "B2B_AVG_ORDER_VALUE"}.
     * Registry 用此 key 自动收集所有 @Component 实现.
     */
    String getIndicatorCode();

    /**
     * 计算指标值.
     *
     * <p>Sprint 12 Phase B step 5 convention 升级:
     * <ul>
     *   <li><b>Counter strategies</b> (COUNT/SUM): 无业务事件返 {@link BigDecimal#ZERO}
     *       — "今日 0 单" 是真业务事实, 0 是 valid count.</li>
     *   <li><b>Ratio strategies</b> (AVG/percentages/rates): 无样本/无分母返 {@code null}
     *       — "0% reject" 误导成 "perfect quality", 应该让 UI 显示 "—" 而非 "0%".</li>
     * </ul>
     *
     * <p>Caller (IndicatorQueryServiceImpl) 对 null 的处理: skip saveVersion + skip
     * lastValue update, IndicatorValueResult.value 还是 null, 前端 IndicatorValueCard
     * 渲 "—" + 配合 actionHint 引导老板做下一步 (per fool-proof-design Rule 5).
     *
     * @param factoryId 工厂 ID
     * @param periodStart 业务时间窗起点 (caller 传, 可为 null — strategy 决定 default, e.g. MTD)
     * @param periodEnd 业务时间窗终点 (caller 传, 可为 null)
     * @return 计算结果, 或 {@code null} 表示该 ratio strategy 在该 period 无有效样本.
     */
    BigDecimal compute(String factoryId, LocalDate periodStart, LocalDate periodEnd);
}
