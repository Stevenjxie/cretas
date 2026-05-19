package com.cretas.aims.service.pricing;

import java.math.BigDecimal;

/**
 * 价格策略计算引擎 — Canvas-Pricing Phase 4b.
 *
 * <p><strong>Skeleton — sister chat 实现 {@code PricingEngineImpl}.</strong>
 *
 * <p>计算流程 (per spec §3):
 * <pre>
 * 1. 加载启用策略 (factoryId, asOfDate=today, ORDER BY priority ASC)
 * 2. scope_filter_json 过滤 (productCategory / customerGroup / region)
 * 3. 同类型组内取 best (TIERED/PROMOTION/MEMBER/BUNDLE/CYCLE 各自规则)
 * 4. 跨类型按 stackability rules 叠加 (sister chat 决策)
 * 5. 计算 finalPrice = unitPriceList - sum(discounts), guard >= 0
 * 6. Fool-proof warnings (final < cost 不阻塞, 仅 warn)
 * 7. 持久化 PricingApplicationLog (calculate only, simulate skip)
 * </pre>
 *
 * @see PricingResult
 * @see PricingRequest
 */
public interface PricingEngine {

    /**
     * 计算最终单价并持久化审计日志.
     *
     * <p>SalesServiceImpl.createOrderLine 真实调用入口 (sister chat 替换 1 行硬编码价).
     *
     * @param request 请求上下文 (factoryId / productId / quantity / customerId / unitPriceList)
     * @return 包含 finalPrice + 已应用策略列表 + warnings 的结果
     */
    PricingResult calculate(PricingRequest request);

    /**
     * 模拟计算 — 仅 preview, NOT 持久化日志.
     *
     * <p>Canvas UI "模拟" 按钮 + AI Tool {@code pricing_test_calculate} 调用入口.
     *
     * <p><b>Post-review (2026-05-19) — preferred overload</b>: 接受完整 PricingRequest,
     * 包含 customerGroup / productCategory / region / costEstimate 等 scope-filter / 防呆相关字段.
     * 老 5-arg overload 是 backwards-compat wrapper, MEMBER / 按类目过滤 等场景必须用此 overload.
     *
     * @param request 完整请求上下文 (含 customerGroup / productCategory / costEstimate / etc.)
     * @return 仅预览结果, 不写日志
     */
    PricingResult simulate(PricingRequest request);

    /**
     * 模拟计算 — 5-arg backwards-compat overload (delegates to {@link #simulate(PricingRequest)}).
     *
     * <p><b>WARNING</b>: This signature drops customerGroup / productCategory — 任何
     * MEMBER strategy 或 scope_filter_json 过滤都不会生效. 优先使用 {@link #simulate(PricingRequest)}.
     *
     * @param factoryId 工厂ID
     * @param productId 商品ID
     * @param quantity 数量
     * @param unitPriceList 标价 (商品 master 取)
     * @param customerId 客户ID (可空, 匿名询价)
     * @return 仅预览结果, 不写日志
     * @deprecated since 2026-05-19 (Phase 4b post-review). Use {@link #simulate(PricingRequest)} —
     *     this overload 静默 drop customerGroup / productCategory.
     */
    @Deprecated
    PricingResult simulate(String factoryId, String productId, int quantity,
                           BigDecimal unitPriceList, Long customerId);
}
