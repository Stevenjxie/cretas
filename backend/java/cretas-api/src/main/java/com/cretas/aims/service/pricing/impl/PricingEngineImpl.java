package com.cretas.aims.service.pricing.impl;

import com.cretas.aims.entity.pricing.PricingApplicationLog;
import com.cretas.aims.entity.pricing.PricingStrategy;
import com.cretas.aims.entity.pricing.PricingStrategyType;
import com.cretas.aims.repository.pricing.PricingApplicationLogRepository;
import com.cretas.aims.repository.pricing.PricingStrategyRepository;
import com.cretas.aims.service.canvas.ThresholdKeys;
import com.cretas.aims.service.canvas.ThresholdResolverService;
import com.cretas.aims.service.pricing.AppliedStrategy;
import com.cretas.aims.service.pricing.PricingEngine;
import com.cretas.aims.service.pricing.PricingRequest;
import com.cretas.aims.service.pricing.PricingResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 价格策略计算引擎实现 — Canvas-Pricing Phase 4b.
 *
 * <p>Sister chat 实现 (per spec §3):
 * <ol>
 *   <li>Load active strategies via
 *       {@code strategyRepo.findActiveByFactoryIdOrderByPriorityAsc(factoryId, today)}</li>
 *   <li>Filter by {@code scope_filter_json} matching
 *       (productCategory / customerGroup / region)</li>
 *   <li>Apply per-strategy by type (TIERED / PROMOTION / MEMBER / BUNDLE / CYCLE)</li>
 *   <li>Aggregate discounts to compute finalPrice (guard &gt;= 0)</li>
 *   <li>Fool-proof warnings (per spec §4):
 *     <ul>
 *       <li>finalPrice &lt; cost → warn "final price ¥X below cost ¥Y"</li>
 *       <li>totalDiscount &gt; 50% of original → warn "deep discount"</li>
 *     </ul>
 *     <strong>NOT BLOCK — warnings only.</strong></li>
 *   <li>{@link #calculate}: persist {@code PricingApplicationLog} row.
 *       {@link #simulate}: skip persistence.</li>
 * </ol>
 *
 * <p><b>Cost source (per spec §10 Q1)</b>: caller may pass {@code costEstimate} through
 * {@link PricingRequest#getCostEstimate()}. NULL = skip cost warning (cost source lookup
 * deferred to caller — typically product master {@code standard_cost} or
 * {@code RawMaterialMovingAverage}). See {@code SalesServiceImpl} sister chat for wiring.
 *
 * @see com.cretas.aims.service.pricing.PricingEngine
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PricingEngineImpl implements PricingEngine {

    private final PricingStrategyRepository strategyRepo;
    private final PricingApplicationLogRepository logRepo;

    /**
     * Canvas-Thresholds resolver — read per-factory deep-discount ratio override when present,
     * otherwise fall back to the hard-coded 0.50 constant. Optional injection
     * (required=false) keeps existing unit tests that bypass Spring DI working:
     * resolver==null branch returns the fallback constant.
     */
    @Autowired(required = false)
    private ThresholdResolverService thresholdResolver;

    /** Fool-proof rule (per spec §4): fallback default when ThresholdResolverService is unavailable. */
    private static final BigDecimal FALLBACK_DEEP_DISCOUNT_RATIO = new BigDecimal("0.50");

    /** Whether to skip CYCLE strategies inline (per spec §3 step 4: CYCLE = month-end rebate). */
    private static final boolean SKIP_CYCLE_INLINE = true;

    // ==================== Public entry points ====================

    @Override
    @Transactional
    public PricingResult calculate(PricingRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("PricingRequest 不能为空");
        }
        log.debug("PricingEngine.calculate: factoryId={}, productId={}, qty={}, unitPriceList={}, customerId={}",
                request.getFactoryId(), request.getProductId(), request.getQuantity(),
                request.getUnitPriceList(), request.getCustomerId());

        PricingResult result = computeInternal(request);

        // Persist audit log (calculate-only, simulate skips this)
        persistLog(request, result);

        return result;
    }

    /**
     * Post-review (2026-05-19): preferred simulate overload — accepts full PricingRequest
     * preserving customerGroup / productCategory / costEstimate / region / businessEntity* fields.
     * Does NOT persist PricingApplicationLog.
     */
    @Override
    public PricingResult simulate(PricingRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("PricingRequest 不能为空");
        }
        log.debug("PricingEngine.simulate (no persist): factoryId={}, productId={}, qty={}, customerGroup={}, productCategory={}",
                request.getFactoryId(), request.getProductId(), request.getQuantity(),
                request.getCustomerGroup(), request.getProductCategory());

        return computeInternal(request);
    }

    /**
     * Backwards-compat 5-arg overload — delegates to {@link #simulate(PricingRequest)}.
     *
     * <p><b>WARNING</b>: This signature drops customerGroup / productCategory / region /
     * costEstimate. MEMBER strategies + scope_filter_json category/region 过滤 不会生效.
     * 新 caller 必须用 {@link #simulate(PricingRequest)}.
     */
    @Override
    @Deprecated
    public PricingResult simulate(String factoryId, String productId, int quantity,
                                   BigDecimal unitPriceList, Long customerId) {
        PricingRequest req = PricingRequest.builder()
                .factoryId(factoryId)
                .productId(productId)
                .quantity(quantity)
                .unitPriceList(unitPriceList != null ? unitPriceList : BigDecimal.ZERO)
                .customerId(customerId)
                .build();
        return simulate(req);
    }

    // ==================== Internal compute ====================

    /**
     * Core compute pipeline (shared by calculate / simulate).
     * Per spec §3 steps 1-6 (step 7 persistence is in calculate() only).
     */
    private PricingResult computeInternal(PricingRequest request) {
        BigDecimal unitPriceList = request.getUnitPriceList() != null
                ? request.getUnitPriceList() : BigDecimal.ZERO;
        int quantity = request.getQuantity() != null ? request.getQuantity() : 1;

        // originalPrice = unitPriceList * quantity (line total before any strategy)
        BigDecimal originalPrice = unitPriceList.multiply(BigDecimal.valueOf(quantity));

        // Step 1: load active strategies, ordered by priority asc
        List<PricingStrategy> activeStrategies =
                strategyRepo.findActiveByFactoryIdOrderByPriorityAsc(
                        request.getFactoryId(), LocalDate.now());

        // Step 2 + 3: filter by scope, then apply by strategy type
        BigDecimal finalPrice = originalPrice;
        List<AppliedStrategy> applied = new ArrayList<>();

        for (PricingStrategy strategy : activeStrategies) {
            if (!scopeMatch(strategy, request)) {
                continue;
            }
            // Skip CYCLE inline — spec §3 step 4: CYCLE = month-end rebate not line-applied.
            if (strategy.getStrategyType() == PricingStrategyType.CYCLE && SKIP_CYCLE_INLINE) {
                log.debug("跳过 CYCLE 策略 (月度结算, NOT inline): {}", strategy.getStrategyCode());
                continue;
            }

            BigDecimal discount = applyStrategy(strategy, request, unitPriceList, finalPrice);
            if (discount != null && discount.compareTo(BigDecimal.ZERO) > 0) {
                finalPrice = finalPrice.subtract(discount);
                applied.add(AppliedStrategy.builder()
                        .strategyId(strategy.getId())
                        .strategyCode(strategy.getStrategyCode())
                        .strategyType(strategy.getStrategyType())
                        .discountApplied(discount)
                        .build());
                log.debug("应用策略 {}: type={}, discount={}, runningFinal={}",
                        strategy.getStrategyCode(), strategy.getStrategyType(), discount, finalPrice);
            }
        }

        // Step 5: guard finalPrice >= 0 (per spec §3.5) — surface as fool-proof warning per Rule 1
        // (post-review I8: don't silently clamp, sales manager needs visibility into stack-overflow)
        List<String> clampWarnings = new ArrayList<>();
        if (finalPrice.compareTo(BigDecimal.ZERO) < 0) {
            BigDecimal totalAttemptedDiscount = originalPrice.subtract(finalPrice);
            String clampWarning = String.format(
                    "折扣总额 ¥%s 超过原价 ¥%s, 自动调整为 0 元 — 请检查策略叠加规则",
                    totalAttemptedDiscount.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                    originalPrice.setScale(2, RoundingMode.HALF_UP).toPlainString());
            log.warn(clampWarning);
            clampWarnings.add(clampWarning);
            finalPrice = BigDecimal.ZERO;
        }

        BigDecimal totalDiscount = originalPrice.subtract(finalPrice);

        // Step 6: fool-proof warnings (NOT blocking) — prepend clamp warning if any
        List<String> warnings = computeWarnings(request, originalPrice, finalPrice, totalDiscount);
        if (!clampWarnings.isEmpty()) {
            // Prepend clamp warning so it shows first (most critical) in UI display order
            warnings.addAll(0, clampWarnings);
        }

        return PricingResult.builder()
                .originalPrice(originalPrice)
                .finalPrice(finalPrice)
                .totalDiscount(totalDiscount)
                .appliedStrategies(applied)
                .warnings(warnings)
                .build();
    }

    // ==================== Scope matching ====================

    /**
     * scope_filter_json match against request.
     *
     * <p>Three filter keys (per entity Javadoc):
     * <ul>
     *   <li>productCategories — match request.productCategory (or request.productId fallback)</li>
     *   <li>customerGroups — match request.customerGroup (or request.customerId)</li>
     *   <li>regions — match request.region</li>
     * </ul>
     * Filter key absent / empty list → not enforced (universal).
     * Filter key present but value-not-in-list → reject.
     */
    private boolean scopeMatch(PricingStrategy strategy, PricingRequest request) {
        Map<String, Object> scope = strategy.getScopeFilterJson();
        if (scope == null || scope.isEmpty()) {
            return true;
        }

        // productCategories
        Collection<?> productCategories = asCollection(scope.get("productCategories"));
        if (productCategories != null && !productCategories.isEmpty()) {
            String productCategory = request.getProductCategory();
            String productId = request.getProductId();
            boolean match = (productCategory != null && productCategories.contains(productCategory))
                    || (productId != null && productCategories.contains(productId));
            if (!match) return false;
        }

        // customerGroups (match by customerGroup string or customerId stringified)
        Collection<?> customerGroups = asCollection(scope.get("customerGroups"));
        if (customerGroups != null && !customerGroups.isEmpty()) {
            String customerGroup = request.getCustomerGroup();
            Long customerId = request.getCustomerId();
            boolean match = (customerGroup != null && customerGroups.contains(customerGroup))
                    || (customerId != null && customerGroups.contains(customerId.toString()));
            if (!match) return false;
        }

        // regions
        Collection<?> regions = asCollection(scope.get("regions"));
        if (regions != null && !regions.isEmpty()) {
            String region = request.getRegion();
            if (region == null || !regions.contains(region)) return false;
        }

        return true;
    }

    // ==================== Per-strategy apply ====================

    /**
     * Returns discount amount (positive) on line total (qty * unitPriceList).
     * Returns null or zero = no discount applied.
     *
     * @param strategy strategy entity (rulesJson per type, see entity Javadoc)
     * @param request the pricing request
     * @param unitPriceList list price (per unit)
     * @param currentLineTotal current running line total (after prior strategies)
     */
    private BigDecimal applyStrategy(PricingStrategy strategy, PricingRequest request,
                                      BigDecimal unitPriceList, BigDecimal currentLineTotal) {
        Map<String, Object> rules = strategy.getRulesJson();
        if (rules == null) return BigDecimal.ZERO;

        // Post-review I1: defensive null guard — corrupt DB row 不应让 engine NPE
        if (strategy.getStrategyType() == null) {
            log.warn("策略 {} 类型为空, 跳过 (DB 数据异常需排查)", strategy.getStrategyCode());
            return BigDecimal.ZERO;
        }

        switch (strategy.getStrategyType()) {
            case TIERED:
                return applyTiered(rules, request, unitPriceList);
            case PROMOTION:
                return applyPromotion(rules, request, unitPriceList, currentLineTotal);
            case MEMBER:
                return applyMember(rules, request, currentLineTotal);
            case BUNDLE:
                return applyBundle(rules, request, currentLineTotal);
            case CYCLE:
                return applyCycle(rules, request, currentLineTotal);
            default:
                log.warn("未知策略类型: {}", strategy.getStrategyType());
                return BigDecimal.ZERO;
        }
    }

    /**
     * TIERED: rulesJson.tiers = [{minQty, maxQty, discountPct}, ...].
     * Find tier where qty falls in [minQty, maxQty], apply discountPct to line total.
     * If multiple tiers match (overlapping ranges), pick highest discountPct.
     */
    private BigDecimal applyTiered(Map<String, Object> rules, PricingRequest request, BigDecimal unitPriceList) {
        List<?> tiers = asList(rules.get("tiers"));
        if (tiers == null || tiers.isEmpty()) return BigDecimal.ZERO;

        int qty = request.getQuantity() != null ? request.getQuantity() : 0;
        BigDecimal bestDiscountPct = null;

        for (Object t : tiers) {
            if (!(t instanceof Map)) continue;
            Map<?, ?> tier = (Map<?, ?>) t;
            int minQty = asInt(tier.get("minQty"), 0);
            int maxQty = asInt(tier.get("maxQty"), Integer.MAX_VALUE);
            BigDecimal discountPct = asDecimal(tier.get("discountPct"));

            if (qty >= minQty && qty <= maxQty && discountPct != null) {
                if (bestDiscountPct == null || discountPct.compareTo(bestDiscountPct) > 0) {
                    bestDiscountPct = discountPct;
                }
            }
        }

        if (bestDiscountPct == null) return BigDecimal.ZERO;

        // line total = qty * unitPriceList; discount = lineTotal * discountPct / 100
        BigDecimal lineTotal = unitPriceList.multiply(BigDecimal.valueOf(qty));
        return percentOf(lineTotal, bestDiscountPct);
    }

    /**
     * PROMOTION: rulesJson.thresholdAmount (line total >= threshold)
     *            OR rulesJson.discountRate (0-1, applied unconditionally)
     *            OR rulesJson.discountAmount (flat amount off).
     */
    private BigDecimal applyPromotion(Map<String, Object> rules, PricingRequest request,
                                       BigDecimal unitPriceList, BigDecimal currentLineTotal) {
        BigDecimal threshold = asDecimal(rules.get("thresholdAmount"));
        BigDecimal discountAmount = asDecimal(rules.get("discountAmount"));
        BigDecimal discountRate = asDecimal(rules.get("discountRate"));   // 0-1 (fractional)
        BigDecimal discountPct = asDecimal(rules.get("discountPct"));     // 0-100 (percent)

        BigDecimal lineTotal = unitPriceList.multiply(
                BigDecimal.valueOf(request.getQuantity() != null ? request.getQuantity() : 1));

        // Threshold gate: line total must meet threshold (NULL = unconditional).
        if (threshold != null && lineTotal.compareTo(threshold) < 0) {
            return BigDecimal.ZERO;
        }

        // Prefer flat discountAmount, then discountRate (0-1), then discountPct (0-100).
        if (discountAmount != null) {
            return discountAmount;
        }
        if (discountRate != null) {
            // Post-review I3: enforce 2-scale HALF_UP to match percentOf path
            // (raw multiply would produce e.g. 0.05 * 100.00 = 5.0000 not 5.00)
            return lineTotal.multiply(discountRate).setScale(2, RoundingMode.HALF_UP);
        }
        if (discountPct != null) {
            return percentOf(lineTotal, discountPct);
        }
        return BigDecimal.ZERO;
    }

    /**
     * MEMBER: rulesJson.membershipTier + rulesJson.discountPct (single tier shape per spec §1).
     *         OR rulesJson.tierDiscounts = {"VIP": 5, "A": 3, ...} (map shape).
     *
     * <p>customer's tier is looked up from request.customerGroup. If customerGroup NULL, MEMBER N/A.
     */
    private BigDecimal applyMember(Map<String, Object> rules, PricingRequest request, BigDecimal currentLineTotal) {
        String customerGroup = request.getCustomerGroup();
        if (customerGroup == null || customerGroup.isBlank()) {
            return BigDecimal.ZERO;   // 匿名 / 无客户上下文 → MEMBER 不适用
        }

        // Shape 1: single-tier { "membershipTier": "VIP", "discountPct": 5 }
        String requiredTier = asString(rules.get("membershipTier"));
        if (requiredTier != null && !requiredTier.equalsIgnoreCase(customerGroup)) {
            return BigDecimal.ZERO;
        }

        BigDecimal discountPct = asDecimal(rules.get("discountPct"));
        if (discountPct != null) {
            return percentOf(currentLineTotal, discountPct);
        }

        // Shape 2: map of tier -> discountPct { "tierDiscounts": { "VIP": 5, "A": 3 } }
        @SuppressWarnings("unchecked")
        Map<String, Object> tierDiscounts = rules.get("tierDiscounts") instanceof Map
                ? (Map<String, Object>) rules.get("tierDiscounts") : null;
        if (tierDiscounts != null) {
            BigDecimal pct = asDecimal(tierDiscounts.get(customerGroup));
            if (pct != null) {
                return percentOf(currentLineTotal, pct);
            }
        }
        return BigDecimal.ZERO;
    }

    /**
     * BUNDLE: rulesJson.items = [{productId, qty}, ...] + rulesJson.bundlePrice
     *      OR rulesJson.discountPct (if cart contains all bundle items at min qty).
     *
     * <p><b>Phase 4b limitation</b>: SO-line context only — we get one productId per request.
     * BUNDLE truly requires multi-line cart context. Here we apply only if request.productId is
     * present in the bundle items list (best-effort single-line approximation). Sister chat will
     * extend in SalesServiceImpl multi-line wire (deferred per spec §9).
     */
    private BigDecimal applyBundle(Map<String, Object> rules, PricingRequest request, BigDecimal currentLineTotal) {
        List<?> items = asList(rules.get("items"));
        if (items == null || items.isEmpty()) return BigDecimal.ZERO;

        // Single-line approximation: only apply if this line's product is in the bundle.
        String productId = request.getProductId();
        if (productId == null) return BigDecimal.ZERO;

        boolean productInBundle = false;
        for (Object item : items) {
            if (item instanceof Map) {
                String bundleProductId = asString(((Map<?, ?>) item).get("productId"));
                if (productId.equals(bundleProductId)) {
                    productInBundle = true;
                    break;
                }
            }
        }
        if (!productInBundle) return BigDecimal.ZERO;

        // Apply discountPct if specified
        BigDecimal discountPct = asDecimal(rules.get("discountPct"));
        if (discountPct != null) {
            return percentOf(currentLineTotal, discountPct);
        }
        // Apply discountAmount if specified
        BigDecimal discountAmount = asDecimal(rules.get("discountAmount"));
        if (discountAmount != null) {
            return discountAmount;
        }
        return BigDecimal.ZERO;
    }

    /**
     * CYCLE: rulesJson.cycle (MONTH / QUARTER / YEAR) + rulesJson.tiers (cumulative threshold).
     *
     * <p>Per spec §3 step 4: <b>CYCLE applied as month-end rebate, NOT inline on SO line</b>.
     * Default behavior: skip (see {@link #SKIP_CYCLE_INLINE}).
     *
     * <p>Returns 0 unless explicitly enabled (sister chat decides Day1 vs Day14 — see spec §10 Q2).
     * Method present for completeness — caller skips at switch level when SKIP_CYCLE_INLINE=true.
     */
    private BigDecimal applyCycle(Map<String, Object> rules, PricingRequest request, BigDecimal currentLineTotal) {
        // Currently disabled (SKIP_CYCLE_INLINE=true at top, caller skips before reaching here).
        // When enabled in future: query historical cumulative sales for customer in current cycle,
        // compare to tiers, return rebatePct * currentLineTotal.
        log.debug("CYCLE strategy invoked inline (rare path) — skipping, rebate is month-end batch job.");
        return BigDecimal.ZERO;
    }

    // ==================== Fool-proof warnings ====================

    /**
     * Per spec §4 — warnings NOT blocking. Sales manager can override.
     * <ul>
     *   <li>finalPrice &lt; costEstimate*qty → warn (cost source from request.costEstimate, NULL = skip)</li>
     *   <li>totalDiscount &gt; 50% of original → warn "deep discount"</li>
     * </ul>
     */
    private List<String> computeWarnings(PricingRequest request, BigDecimal originalPrice,
                                          BigDecimal finalPrice, BigDecimal totalDiscount) {
        List<String> warnings = new ArrayList<>();
        BigDecimal costEstimate = request.getCostEstimate();
        int qty = request.getQuantity() != null ? request.getQuantity() : 1;

        if (costEstimate != null && qty > 0) {
            BigDecimal totalCost = costEstimate.multiply(BigDecimal.valueOf(qty));
            if (finalPrice.compareTo(totalCost) < 0) {
                warnings.add(String.format("final price ¥%s below cost ¥%s — 请销售经理审核",
                        finalPrice.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                        totalCost.setScale(2, RoundingMode.HALF_UP).toPlainString()));
            }
        }

        if (originalPrice.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal discountRatio = totalDiscount.divide(originalPrice, 4, RoundingMode.HALF_UP);
            BigDecimal threshold = resolveDeepDiscountRatio(request.getFactoryId());
            if (discountRatio.compareTo(threshold) > 0) {
                BigDecimal thresholdPct = threshold.multiply(BigDecimal.valueOf(100))
                        .setScale(0, RoundingMode.HALF_UP);
                warnings.add(String.format("deep discount — 折扣 %s%% 超过 %s%% 阈值",
                        discountRatio.multiply(BigDecimal.valueOf(100))
                                .setScale(1, RoundingMode.HALF_UP).toPlainString(),
                        thresholdPct.toPlainString()));
            }
        }
        return warnings;
    }

    /**
     * Resolve the deep-discount warning ratio for the given factory.
     * Falls back to {@link #FALLBACK_DEEP_DISCOUNT_RATIO} (0.50) when the resolver bean
     * is not present in the application context (e.g. unit tests).
     */
    private BigDecimal resolveDeepDiscountRatio(String factoryId) {
        if (thresholdResolver == null) {
            return FALLBACK_DEEP_DISCOUNT_RATIO;
        }
        return thresholdResolver.getBigDecimal(
                factoryId, ThresholdKeys.PRICING_DEEP_DISCOUNT_RATIO, FALLBACK_DEEP_DISCOUNT_RATIO);
    }

    // ==================== Persistence ====================

    private void persistLog(PricingRequest request, PricingResult result) {
        try {
            PricingApplicationLog logEntry = new PricingApplicationLog();
            logEntry.setFactoryId(request.getFactoryId());
            logEntry.setBusinessEntityType(request.getBusinessEntityType());
            logEntry.setBusinessEntityId(request.getBusinessEntityId());
            logEntry.setOriginalPrice(result.getOriginalPrice());
            logEntry.setFinalPrice(result.getFinalPrice());
            logEntry.setDiscount(result.getTotalDiscount());

            // strategyId: pick first applied (rare — multi-strategy stack is uncommon).
            // Full applied list goes into appliedStrategies JSONB for audit.
            if (result.getAppliedStrategies() != null && !result.getAppliedStrategies().isEmpty()) {
                logEntry.setStrategyId(result.getAppliedStrategies().get(0).getStrategyId());
            }
            logEntry.setAppliedStrategies(toJsonbList(result.getAppliedStrategies()));
            logEntry.setAppliedAt(LocalDateTime.now());

            logRepo.save(logEntry);
        } catch (Exception e) {
            // Post-review I6: audit log persist failure is NOT silent.
            // Per api-response-handling.md 禁止降级处理 — surface to caller via result.warnings.
            // Pricing itself succeeded (correct discount applied to SO line); only the audit
            // trail is missing. Don't throw — that would block pricing & lose customer order.
            // But MUST be visible to ops so finance month-end reconciliation can patch.
            String strategyId = result.getAppliedStrategies() != null && !result.getAppliedStrategies().isEmpty()
                    ? result.getAppliedStrategies().get(0).getStrategyId() : null;
            log.error("PricingApplicationLog persist FAILED: factoryId={}, strategyId={}, businessEntityType={}, businessEntityId={}",
                    request.getFactoryId(), strategyId,
                    request.getBusinessEntityType(), request.getBusinessEntityId(), e);
            if (result.getWarnings() != null) {
                result.getWarnings().add(
                        "审计日志写入失败 — 请联系运维核对 (折扣已应用, 但财务月度核算可能缺失记录)");
            }
        }
    }

    private List<Map<String, Object>> toJsonbList(List<AppliedStrategy> applied) {
        if (applied == null || applied.isEmpty()) return new ArrayList<>();
        List<Map<String, Object>> out = new ArrayList<>(applied.size());
        for (AppliedStrategy a : applied) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("strategyId", a.getStrategyId());
            entry.put("strategyCode", a.getStrategyCode());
            entry.put("type", a.getStrategyType() != null ? a.getStrategyType().name() : null);
            entry.put("discountApplied", a.getDiscountApplied());
            out.add(entry);
        }
        return out;
    }

    // ==================== Util ====================

    private BigDecimal percentOf(BigDecimal base, BigDecimal pct) {
        if (base == null || pct == null) return BigDecimal.ZERO;
        return base.multiply(pct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal asDecimal(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal) return (BigDecimal) v;
        if (v instanceof Number) return BigDecimal.valueOf(((Number) v).doubleValue());
        try {
            return new BigDecimal(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int asInt(Object v, int fallback) {
        if (v == null) return fallback;
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private String asString(Object v) {
        return v != null ? v.toString() : null;
    }

    private Collection<?> asCollection(Object v) {
        if (v instanceof Collection) return (Collection<?>) v;
        return null;
    }

    private List<?> asList(Object v) {
        if (v instanceof List) return (List<?>) v;
        return null;
    }
}
