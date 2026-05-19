package com.cretas.aims.service.pricing.impl;

import com.cretas.aims.repository.pricing.PricingApplicationLogRepository;
import com.cretas.aims.repository.pricing.PricingStrategyRepository;
import com.cretas.aims.service.pricing.PricingEngine;
import com.cretas.aims.service.pricing.PricingRequest;
import com.cretas.aims.service.pricing.PricingResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 价格策略计算引擎实现 — Canvas-Pricing Phase 4b SKELETON.
 *
 * <p><strong>⚠️ SKELETON: 所有方法 throw UnsupportedOperationException.
 * Sister chat 实现完整逻辑 (2-3 天工程量, 见 spec §9).</strong>
 *
 * <p>Sister chat to implement (per spec §3):
 * <ol>
 *   <li>Load active strategies via
 *       {@code strategyRepo.findActiveByFactoryIdOrderByPriorityAsc(factoryId, today)}</li>
 *   <li>Filter by {@code scope_filter_json} matching
 *       (productCategory / customerGroup / region)</li>
 *   <li>Group by {@code strategyType}; within type pick BEST:
 *     <ul>
 *       <li>TIERED: highest qualifying tier (qty &gt;= minQty &amp;&amp; qty &lt;= maxQty)</li>
 *       <li>PROMOTION: largest discount where threshold met</li>
 *       <li>MEMBER: customer's tier match</li>
 *       <li>BUNDLE: cart contains all items (cart-aware, requires SalesService context)</li>
 *       <li>CYCLE: customer cumulative — Day1 vs Day14 decision (see spec §10)</li>
 *     </ul>
 *   </li>
 *   <li>Inter-type stackability: default MEMBER + (best of TIERED|PROMOTION|BUNDLE).
 *       CYCLE applied as month-end rebate (NOT inline on SO line).
 *       Stackability flag in {@code rulesJson.stackableWith}.</li>
 *   <li>finalPrice = unitPriceList − sum(discounts), guard &gt;= 0.</li>
 *   <li>Fool-proof warnings (per spec §4):
 *     <ul>
 *       <li>finalPrice &lt; cost → warn "final price ¥X below cost ¥Y"
 *           (cost source: see spec §10 Q1)</li>
 *       <li>totalDiscount &gt; 50% of unitPriceList → warn "deep discount"</li>
 *     </ul>
 *     <strong>NOT BLOCK — warnings only.</strong></li>
 *   <li>{@link #calculate}: persist {@code PricingApplicationLog} row.
 *       {@link #simulate}: skip persistence.</li>
 * </ol>
 *
 * @see com.cretas.aims.service.pricing.PricingEngine
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PricingEngineImpl implements PricingEngine {

    private final PricingStrategyRepository strategyRepo;
    private final PricingApplicationLogRepository logRepo;

    @Override
    public PricingResult calculate(PricingRequest request) {
        log.warn("PricingEngineImpl.calculate skeleton invoked — sister chat to implement. request={}", request);
        throw new UnsupportedOperationException(
                "PricingEngineImpl.calculate not yet implemented. Sister chat to fill — see PricingEngineImpl Javadoc + spec §3."
        );
    }

    @Override
    public PricingResult simulate(String factoryId, String productId, int quantity,
                                   BigDecimal unitPriceList, Long customerId) {
        log.warn("PricingEngineImpl.simulate skeleton invoked — sister chat to implement. " +
                "factoryId={}, productId={}, quantity={}, customerId={}",
                factoryId, productId, quantity, customerId);
        throw new UnsupportedOperationException(
                "PricingEngineImpl.simulate not yet implemented. Sister chat to fill — see PricingEngineImpl Javadoc + spec §3."
        );
    }
}
