package com.cretas.aims.service.pricing.impl;

import com.cretas.aims.entity.pricing.PricingApplicationLog;
import com.cretas.aims.entity.pricing.PricingStrategy;
import com.cretas.aims.entity.pricing.PricingStrategyType;
import com.cretas.aims.repository.pricing.PricingApplicationLogRepository;
import com.cretas.aims.repository.pricing.PricingStrategyRepository;
import com.cretas.aims.service.pricing.AppliedStrategy;
import com.cretas.aims.service.pricing.PricingRequest;
import com.cretas.aims.service.pricing.PricingResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PricingEngineImpl}.
 *
 * <p>Coverage matrix (per spec §3 + §4):
 * <ol>
 *   <li>TIERED — picks correct tier by quantity, applies discountPct</li>
 *   <li>PROMOTION — applies discountAmount when threshold met</li>
 *   <li>PROMOTION — skipped when threshold NOT met</li>
 *   <li>MEMBER — applies discountPct for matching membershipTier</li>
 *   <li>MEMBER — N/A when customerGroup is null (anonymous quote)</li>
 *   <li>BUNDLE — applies discountPct when productId in bundle items</li>
 *   <li>CYCLE — skipped inline (month-end rebate)</li>
 *   <li>Multi-strategy stacking — TIERED + MEMBER applied to same line</li>
 *   <li>Fool-proof warning — finalPrice &lt; cost triggers warning</li>
 *   <li>Fool-proof warning — deep discount &gt; 50% triggers warning</li>
 *   <li>finalPrice clamped to 0 (negative guard)</li>
 *   <li>scope_filter_json mismatch — strategy skipped</li>
 *   <li>simulate() — does NOT persist log</li>
 *   <li>calculate() — persists log row</li>
 * </ol>
 */
@DisplayName("PricingEngineImpl — Canvas-Pricing Phase 4b")
@ExtendWith(MockitoExtension.class)
class PricingEngineImplTest {

    @Mock private PricingStrategyRepository strategyRepo;
    @Mock private PricingApplicationLogRepository logRepo;
    @InjectMocks private PricingEngineImpl engine;

    private static final String FACTORY_ID = "F006";

    @BeforeEach
    void setUp() {
        // Default: no strategies loaded — individual tests override
        lenient().when(strategyRepo.findActiveByFactoryIdOrderByPriorityAsc(anyString(), any(LocalDate.class)))
                .thenReturn(new ArrayList<>());
    }

    // ==================== TIERED ====================

    @Test
    @DisplayName("TIERED — qty=600 matches tier {minQty:500, discountPct:5}, applies 5% off")
    void tiered_appliesTierMatchingQuantity() {
        // tiers: 0-99 → 0%, 100-499 → 3%, 500+ → 5%
        Map<String, Object> rules = mapOf("tiers", Arrays.asList(
                mapOf("minQty", 0, "maxQty", 99, "discountPct", new BigDecimal("0")),
                mapOf("minQty", 100, "maxQty", 499, "discountPct", new BigDecimal("3")),
                mapOf("minQty", 500, "discountPct", new BigDecimal("5"))   // no maxQty = unbounded
        ));
        PricingStrategy tiered = buildStrategy("TIERED_DD", PricingStrategyType.TIERED, rules, 100);
        when(strategyRepo.findActiveByFactoryIdOrderByPriorityAsc(eq(FACTORY_ID), any(LocalDate.class)))
                .thenReturn(List.of(tiered));

        PricingRequest req = baseRequest()
                .quantity(new BigDecimal("600"))
                .unitPriceList(new BigDecimal("10.00"))
                .build();

        PricingResult result = engine.calculate(req);

        // qty=600 × unitPrice=10 = 6000 original; 5% off = discount 300, final = 5700
        assertEquals(new BigDecimal("6000.00"), result.getOriginalPrice());
        assertEquals(new BigDecimal("5700.00"), result.getFinalPrice());
        assertEquals(new BigDecimal("300.00"), result.getTotalDiscount());
        assertEquals(1, result.getAppliedStrategies().size());
        assertEquals(PricingStrategyType.TIERED, result.getAppliedStrategies().get(0).getStrategyType());
    }

    // ==================== Fractional quantity (Integer→BigDecimal widening) ====================

    @Test
    @DisplayName("Fractional qty — 2.5 × ¥50 → line total ¥125 (NOT truncated to 2 × ¥50 = ¥100)")
    void fractionalQty_notTruncated_usesFullQuantity() {
        // Pre-widening, PricingRequest.quantity was Integer and SalesServiceImpl passed
        // getQuantity().intValue() → 2.5 became 2 → ¥100. Now the full 2.5 is used → ¥125.
        PricingRequest req = baseRequest()
                .quantity(new BigDecimal("2.5"))
                .unitPriceList(new BigDecimal("50.00"))
                .build();
        PricingResult result = engine.calculate(req);
        assertEquals(0, new BigDecimal("125.00").compareTo(result.getOriginalPrice()),
                "fractional qty must not be truncated to an integer");
        assertEquals(0, new BigDecimal("125.00").compareTo(result.getFinalPrice()));
    }

    @Test
    @DisplayName("Fractional qty — 2.5 matches integer tier [2,10] @10%, discount on full fractional line")
    void fractionalQty_matchesIntegerTier() {
        Map<String, Object> rules = mapOf("tiers", List.of(
                mapOf("minQty", 2, "maxQty", 10, "discountPct", new BigDecimal("10"))));
        when(strategyRepo.findActiveByFactoryIdOrderByPriorityAsc(eq(FACTORY_ID), any(LocalDate.class)))
                .thenReturn(List.of(buildStrategy("TIER_FRAC", PricingStrategyType.TIERED, rules, 100)));
        PricingRequest req = baseRequest()
                .quantity(new BigDecimal("2.5"))
                .unitPriceList(new BigDecimal("50.00"))
                .build();
        PricingResult result = engine.calculate(req);
        // line 125, 10% off = 12.50 discount → final 112.50
        assertEquals(0, new BigDecimal("125.00").compareTo(result.getOriginalPrice()));
        assertEquals(0, new BigDecimal("112.50").compareTo(result.getFinalPrice()));
        assertEquals(0, new BigDecimal("12.50").compareTo(result.getTotalDiscount()));
    }

    @Test
    @DisplayName("Fractional qty — 1.9 does NOT match tier [2,10] (no round-up to 2, no discount)")
    void fractionalQty_belowTierMin_noDiscount() {
        Map<String, Object> rules = mapOf("tiers", List.of(
                mapOf("minQty", 2, "maxQty", 10, "discountPct", new BigDecimal("10"))));
        when(strategyRepo.findActiveByFactoryIdOrderByPriorityAsc(eq(FACTORY_ID), any(LocalDate.class)))
                .thenReturn(List.of(buildStrategy("TIER_FRAC2", PricingStrategyType.TIERED, rules, 100)));
        PricingRequest req = baseRequest()
                .quantity(new BigDecimal("1.9"))
                .unitPriceList(new BigDecimal("50.00"))
                .build();
        PricingResult result = engine.calculate(req);
        // 1.9 < minQty 2 → no match; line = 1.9 × 50 = 95, no discount
        assertEquals(0, new BigDecimal("95.00").compareTo(result.getFinalPrice()));
        assertEquals(0, BigDecimal.ZERO.compareTo(result.getTotalDiscount()));
    }

    @Test
    @DisplayName("Fractional TIER boundary — tier {minQty:2.5} inclusively matches qty 2.5")
    void fractionalTierBoundary_inclusiveMatch() {
        Map<String, Object> rules = mapOf("tiers", List.of(
                mapOf("minQty", new BigDecimal("2.5"), "maxQty", new BigDecimal("10"),
                        "discountPct", new BigDecimal("20"))));
        when(strategyRepo.findActiveByFactoryIdOrderByPriorityAsc(eq(FACTORY_ID), any(LocalDate.class)))
                .thenReturn(List.of(buildStrategy("TIER_FRAC3", PricingStrategyType.TIERED, rules, 100)));
        PricingRequest req = baseRequest()
                .quantity(new BigDecimal("2.5"))
                .unitPriceList(new BigDecimal("50.00"))
                .build();
        PricingResult result = engine.calculate(req);
        // 2.5 matches [2.5,10] inclusive; 20% off 125 = 25 discount → final 100
        assertEquals(0, new BigDecimal("100.00").compareTo(result.getFinalPrice()));
        assertEquals(0, new BigDecimal("25.00").compareTo(result.getTotalDiscount()));
    }

    @Test
    @DisplayName("Fractional TIER boundary from JSON Double — {minQty:0.5(Double)} matches qty 0.5 (asDecimal exact)")
    void fractionalTierBoundary_jsonDoubleType_exactMatch() {
        // Tier boundaries arrive from rulesJson as boxed Double/Integer (not BigDecimal). asDecimal
        // must round-trip them through String, NOT doubleValue() — otherwise the boxed Double's
        // representation could diverge from a string-built qty and the tier would silently not apply.
        // This case exercises asDecimal's Number branch (the BigDecimal-literal tests bypass it).
        Map<String, Object> rules = mapOf("tiers", List.of(
                mapOf("minQty", Double.valueOf(0.5), "maxQty", Double.valueOf(2.0),
                        "discountPct", new BigDecimal("10"))));
        when(strategyRepo.findActiveByFactoryIdOrderByPriorityAsc(eq(FACTORY_ID), any(LocalDate.class)))
                .thenReturn(List.of(buildStrategy("TIER_JSON_DBL", PricingStrategyType.TIERED, rules, 100)));
        PricingRequest req = baseRequest()
                .quantity(new BigDecimal("0.5"))
                .unitPriceList(new BigDecimal("50.00"))
                .build();
        PricingResult result = engine.calculate(req);
        // 0.5 must match [0.5,2.0]; line 0.5×50=25, 10% off = 2.50 discount → final 22.50
        assertEquals(0, new BigDecimal("25.00").compareTo(result.getOriginalPrice()));
        assertEquals(0, new BigDecimal("22.50").compareTo(result.getFinalPrice()),
                "Double-typed tier boundary 0.5 must match qty 0.5 (asDecimal String round-trip)");
        assertEquals(0, new BigDecimal("2.50").compareTo(result.getTotalDiscount()));
    }

    // ==================== PROMOTION ====================

    @Test
    @DisplayName("PROMOTION — threshold met, applies flat discountAmount")
    void promotion_thresholdMet_appliesDiscountAmount() {
        Map<String, Object> rules = mapOf(
                "thresholdAmount", new BigDecimal("50000"),
                "discountAmount", new BigDecimal("1000")
        );
        PricingStrategy promo = buildStrategy("PROMO_FULL_50K", PricingStrategyType.PROMOTION, rules, 80);
        when(strategyRepo.findActiveByFactoryIdOrderByPriorityAsc(eq(FACTORY_ID), any(LocalDate.class)))
                .thenReturn(List.of(promo));

        PricingRequest req = baseRequest()
                .quantity(new BigDecimal("5000"))
                .unitPriceList(new BigDecimal("10.00"))   // line total = 50000, meets threshold
                .build();

        PricingResult result = engine.calculate(req);

        assertEquals(new BigDecimal("50000.00"), result.getOriginalPrice());
        assertEquals(new BigDecimal("49000.00"), result.getFinalPrice());
        assertEquals(new BigDecimal("1000.00"), result.getTotalDiscount());
        assertEquals(PricingStrategyType.PROMOTION, result.getAppliedStrategies().get(0).getStrategyType());
    }

    @Test
    @DisplayName("PROMOTION — threshold NOT met, no discount applied")
    void promotion_thresholdNotMet_skipped() {
        Map<String, Object> rules = mapOf(
                "thresholdAmount", new BigDecimal("50000"),
                "discountAmount", new BigDecimal("1000")
        );
        PricingStrategy promo = buildStrategy("PROMO_FULL_50K", PricingStrategyType.PROMOTION, rules, 80);
        when(strategyRepo.findActiveByFactoryIdOrderByPriorityAsc(eq(FACTORY_ID), any(LocalDate.class)))
                .thenReturn(List.of(promo));

        PricingRequest req = baseRequest()
                .quantity(new BigDecimal("100"))
                .unitPriceList(new BigDecimal("10.00"))   // line total = 1000, BELOW threshold
                .build();

        PricingResult result = engine.calculate(req);

        assertEquals(new BigDecimal("1000.00"), result.getOriginalPrice());
        assertEquals(new BigDecimal("1000.00"), result.getFinalPrice());
        assertEquals(0, result.getAppliedStrategies().size());
    }

    // ==================== MEMBER ====================

    @Test
    @DisplayName("MEMBER — customerGroup matches membershipTier, applies discountPct")
    void member_tierMatch_appliesDiscountPct() {
        Map<String, Object> rules = mapOf(
                "membershipTier", "VIP",
                "discountPct", new BigDecimal("5")
        );
        PricingStrategy member = buildStrategy("MEMBER_VIP", PricingStrategyType.MEMBER, rules, 60);
        when(strategyRepo.findActiveByFactoryIdOrderByPriorityAsc(eq(FACTORY_ID), any(LocalDate.class)))
                .thenReturn(List.of(member));

        PricingRequest req = baseRequest()
                .quantity(new BigDecimal("10"))
                .unitPriceList(new BigDecimal("100.00"))
                .customerGroup("VIP")
                .build();

        PricingResult result = engine.calculate(req);

        // 10 × 100 = 1000 line; 5% off = 50 discount; final = 950
        assertEquals(new BigDecimal("1000.00"), result.getOriginalPrice());
        assertEquals(new BigDecimal("950.00"), result.getFinalPrice());
        assertEquals(PricingStrategyType.MEMBER, result.getAppliedStrategies().get(0).getStrategyType());
    }

    @Test
    @DisplayName("MEMBER — customerGroup is null (anonymous quote), strategy N/A")
    void member_anonymousCustomer_skipped() {
        Map<String, Object> rules = mapOf("membershipTier", "VIP", "discountPct", new BigDecimal("5"));
        PricingStrategy member = buildStrategy("MEMBER_VIP", PricingStrategyType.MEMBER, rules, 60);
        when(strategyRepo.findActiveByFactoryIdOrderByPriorityAsc(eq(FACTORY_ID), any(LocalDate.class)))
                .thenReturn(List.of(member));

        PricingRequest req = baseRequest()
                .quantity(new BigDecimal("10"))
                .unitPriceList(new BigDecimal("100.00"))
                .customerGroup(null)   // anonymous
                .build();

        PricingResult result = engine.calculate(req);

        assertEquals(new BigDecimal("1000.00"), result.getFinalPrice());
        assertEquals(0, result.getAppliedStrategies().size());
    }

    // ==================== BUNDLE ====================

    @Test
    @DisplayName("BUNDLE — productId in bundle items, applies discountPct (single-line approx)")
    void bundle_productInBundle_appliesDiscount() {
        Map<String, Object> rules = mapOf(
                "items", Arrays.asList(
                        mapOf("productId", "P_HOTPOT", "qty", 2),
                        mapOf("productId", "P_SOUP", "qty", 1)),
                "discountPct", new BigDecimal("10")
        );
        PricingStrategy bundle = buildStrategy("BUNDLE_HOTPOT_SOUP", PricingStrategyType.BUNDLE, rules, 40);
        when(strategyRepo.findActiveByFactoryIdOrderByPriorityAsc(eq(FACTORY_ID), any(LocalDate.class)))
                .thenReturn(List.of(bundle));

        PricingRequest req = baseRequest()
                .productId("P_HOTPOT")   // in bundle
                .quantity(new BigDecimal("2"))
                .unitPriceList(new BigDecimal("200.00"))
                .build();

        PricingResult result = engine.calculate(req);

        // 2 × 200 = 400; 10% off = 40 discount; final 360
        assertEquals(new BigDecimal("400.00"), result.getOriginalPrice());
        assertEquals(new BigDecimal("360.00"), result.getFinalPrice());
        assertEquals(PricingStrategyType.BUNDLE, result.getAppliedStrategies().get(0).getStrategyType());
    }

    // ==================== CYCLE ====================

    @Test
    @DisplayName("CYCLE — skipped inline (month-end rebate, NOT SO-line)")
    void cycle_skippedInline() {
        Map<String, Object> rules = mapOf(
                "cycle", "MONTH",
                "tiers", List.of(mapOf("minAmount", new BigDecimal("100000"), "rebatePct", new BigDecimal("3")))
        );
        PricingStrategy cycle = buildStrategy("CYCLE_MONTHLY", PricingStrategyType.CYCLE, rules, 20);
        when(strategyRepo.findActiveByFactoryIdOrderByPriorityAsc(eq(FACTORY_ID), any(LocalDate.class)))
                .thenReturn(List.of(cycle));

        PricingRequest req = baseRequest()
                .quantity(new BigDecimal("100"))
                .unitPriceList(new BigDecimal("10.00"))
                .build();

        PricingResult result = engine.calculate(req);

        // CYCLE skipped → no discount
        assertEquals(new BigDecimal("1000.00"), result.getFinalPrice());
        assertEquals(0, result.getAppliedStrategies().size());
    }

    // ==================== Stacking ====================

    @Test
    @DisplayName("TIERED + MEMBER stack — both applied to same line")
    void multiStrategy_stacking_tieredPlusMember() {
        Map<String, Object> tieredRules = mapOf("tiers", List.of(
                mapOf("minQty", 100, "discountPct", new BigDecimal("5"))));
        Map<String, Object> memberRules = mapOf("membershipTier", "VIP", "discountPct", new BigDecimal("3"));

        PricingStrategy tiered = buildStrategy("TIERED", PricingStrategyType.TIERED, tieredRules, 50);
        PricingStrategy member = buildStrategy("MEMBER", PricingStrategyType.MEMBER, memberRules, 60);
        when(strategyRepo.findActiveByFactoryIdOrderByPriorityAsc(eq(FACTORY_ID), any(LocalDate.class)))
                .thenReturn(Arrays.asList(tiered, member));

        PricingRequest req = baseRequest()
                .quantity(new BigDecimal("200"))
                .unitPriceList(new BigDecimal("10.00"))
                .customerGroup("VIP")
                .build();

        PricingResult result = engine.calculate(req);

        // original = 2000
        // TIERED 5% off 2000 = 100 → running 1900
        // MEMBER 3% off 1900 = 57 → running 1843
        // total discount = 157
        assertEquals(new BigDecimal("2000.00"), result.getOriginalPrice());
        assertEquals(2, result.getAppliedStrategies().size());
        assertTrue(result.getTotalDiscount().compareTo(BigDecimal.ZERO) > 0);
    }

    // ==================== Fool-proof warnings ====================

    @Test
    @DisplayName("Fool-proof — finalPrice < cost*qty triggers warning (NOT block)")
    void foolProof_finalBelowCost_addsWarning() {
        Map<String, Object> rules = mapOf("tiers", List.of(
                mapOf("minQty", 1, "discountPct", new BigDecimal("60"))));   // 60% off — aggressive
        PricingStrategy tiered = buildStrategy("AGGRESSIVE", PricingStrategyType.TIERED, rules, 50);
        when(strategyRepo.findActiveByFactoryIdOrderByPriorityAsc(eq(FACTORY_ID), any(LocalDate.class)))
                .thenReturn(List.of(tiered));

        PricingRequest req = baseRequest()
                .quantity(new BigDecimal("10"))
                .unitPriceList(new BigDecimal("100.00"))   // line 1000
                .costEstimate(new BigDecimal("60.00"))     // total cost 600
                .build();

        PricingResult result = engine.calculate(req);

        // 60% off 1000 = 600 discount; final 400; cost 600 → final < cost → warning
        assertEquals(new BigDecimal("400.00"), result.getFinalPrice());
        assertFalse(result.getWarnings().isEmpty());
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("below cost")),
                "Expected 'below cost' warning, got: " + result.getWarnings());
        // 60% is also >50% → deep discount warning
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("deep discount") || w.contains("折扣")),
                "Expected deep discount warning, got: " + result.getWarnings());
    }

    @Test
    @DisplayName("Fool-proof — discount > 50% triggers deep discount warning")
    void foolProof_deepDiscount_addsWarning() {
        Map<String, Object> rules = mapOf("tiers", List.of(
                mapOf("minQty", 1, "discountPct", new BigDecimal("55"))));
        PricingStrategy tiered = buildStrategy("DEEP", PricingStrategyType.TIERED, rules, 50);
        when(strategyRepo.findActiveByFactoryIdOrderByPriorityAsc(eq(FACTORY_ID), any(LocalDate.class)))
                .thenReturn(List.of(tiered));

        PricingRequest req = baseRequest()
                .quantity(new BigDecimal("10"))
                .unitPriceList(new BigDecimal("100.00"))
                .build();

        PricingResult result = engine.calculate(req);

        assertFalse(result.getWarnings().isEmpty());
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("deep discount") || w.contains("折扣")),
                "Expected deep discount warning, got: " + result.getWarnings());
    }

    // ==================== Scope filter ====================

    @Test
    @DisplayName("scope_filter_json mismatch — strategy skipped")
    void scopeFilter_mismatch_skipped() {
        Map<String, Object> rules = mapOf("tiers", List.of(
                mapOf("minQty", 1, "discountPct", new BigDecimal("10"))));
        PricingStrategy tiered = buildStrategy("REGION_EAST", PricingStrategyType.TIERED, rules, 50);
        // scope: only customerGroups=["VIP"], but our request customerGroup=NORMAL
        Map<String, Object> scope = mapOf("customerGroups", List.of("VIP"));
        tiered.setScopeFilterJson(scope);
        when(strategyRepo.findActiveByFactoryIdOrderByPriorityAsc(eq(FACTORY_ID), any(LocalDate.class)))
                .thenReturn(List.of(tiered));

        PricingRequest req = baseRequest()
                .quantity(new BigDecimal("10"))
                .unitPriceList(new BigDecimal("100.00"))
                .customerGroup("NORMAL")
                .build();

        PricingResult result = engine.calculate(req);

        assertEquals(0, result.getAppliedStrategies().size());
        assertEquals(new BigDecimal("1000.00"), result.getFinalPrice());
    }

    // ==================== Persistence ====================

    @Test
    @DisplayName("calculate() — persists PricingApplicationLog row")
    void calculate_persistsLog() {
        Map<String, Object> rules = mapOf("tiers", List.of(
                mapOf("minQty", 1, "discountPct", new BigDecimal("5"))));
        PricingStrategy tiered = buildStrategy("LOG_TEST", PricingStrategyType.TIERED, rules, 50);
        when(strategyRepo.findActiveByFactoryIdOrderByPriorityAsc(eq(FACTORY_ID), any(LocalDate.class)))
                .thenReturn(List.of(tiered));

        PricingRequest req = baseRequest()
                .quantity(new BigDecimal("10"))
                .unitPriceList(new BigDecimal("100.00"))
                .businessEntityType("SO_LINE")
                .businessEntityId("SO-LINE-001")
                .build();

        engine.calculate(req);

        ArgumentCaptor<PricingApplicationLog> captor = ArgumentCaptor.forClass(PricingApplicationLog.class);
        verify(logRepo, times(1)).save(captor.capture());
        PricingApplicationLog logged = captor.getValue();
        assertEquals(FACTORY_ID, logged.getFactoryId());
        assertEquals("SO_LINE", logged.getBusinessEntityType());
        assertEquals("SO-LINE-001", logged.getBusinessEntityId());
        assertNotNull(logged.getOriginalPrice());
        assertNotNull(logged.getFinalPrice());
        assertNotNull(logged.getAppliedAt());
    }

    @Test
    @DisplayName("simulate() — does NOT persist log row")
    void simulate_doesNotPersistLog() {
        Map<String, Object> rules = mapOf("tiers", List.of(
                mapOf("minQty", 1, "discountPct", new BigDecimal("5"))));
        PricingStrategy tiered = buildStrategy("SIM_TEST", PricingStrategyType.TIERED, rules, 50);
        when(strategyRepo.findActiveByFactoryIdOrderByPriorityAsc(eq(FACTORY_ID), any(LocalDate.class)))
                .thenReturn(List.of(tiered));

        PricingResult result = engine.simulate(FACTORY_ID, "P-001", new BigDecimal("10"), new BigDecimal("100.00"), 999L);

        assertNotNull(result);
        assertNotNull(result.getFinalPrice());
        verify(logRepo, never()).save(any(PricingApplicationLog.class));
    }

    // ==================== Post-review 2026-05-19: new simulate(PricingRequest) overload ====================

    @Test
    @DisplayName("simulate(PricingRequest) — preserves customerGroup so MEMBER strategy applies")
    void simulateRequestOverload_preservesCustomerGroupForMember() {
        // MEMBER strategy gated on customerGroup=VIP
        Map<String, Object> memberRules = mapOf("membershipTier", "VIP", "discountPct", new BigDecimal("5"));
        PricingStrategy member = buildStrategy("MEMBER_VIP_SIM", PricingStrategyType.MEMBER, memberRules, 60);
        when(strategyRepo.findActiveByFactoryIdOrderByPriorityAsc(eq(FACTORY_ID), any(LocalDate.class)))
                .thenReturn(List.of(member));

        // PR-25 C1 bug: old 5-arg simulate dropped customerGroup, so MEMBER strategy never matched.
        // New overload preserves it.
        PricingRequest req = PricingRequest.builder()
                .factoryId(FACTORY_ID)
                .productId("P_VIP")
                .quantity(new BigDecimal("10"))
                .unitPriceList(new BigDecimal("100.00"))
                .customerId(999L)
                .customerGroup("VIP")
                .build();

        PricingResult result = engine.simulate(req);

        // 5% off 1000 = 50 discount; final 950
        assertEquals(new BigDecimal("950.00"), result.getFinalPrice());
        assertEquals(1, result.getAppliedStrategies().size());
        assertEquals(PricingStrategyType.MEMBER, result.getAppliedStrategies().get(0).getStrategyType());
        // Must NOT persist log on simulate path
        verify(logRepo, never()).save(any(PricingApplicationLog.class));
    }

    @Test
    @DisplayName("simulate(PricingRequest) — preserves productCategory so scope_filter applies")
    void simulateRequestOverload_preservesProductCategoryForScope() {
        Map<String, Object> rules = mapOf("tiers", List.of(
                mapOf("minQty", 1, "discountPct", new BigDecimal("10"))));
        PricingStrategy tiered = buildStrategy("CAT_FROZEN", PricingStrategyType.TIERED, rules, 50);
        // scope: only productCategories=["frozen"]
        tiered.setScopeFilterJson(mapOf("productCategories", List.of("frozen")));
        when(strategyRepo.findActiveByFactoryIdOrderByPriorityAsc(eq(FACTORY_ID), any(LocalDate.class)))
                .thenReturn(List.of(tiered));

        // Request with matching productCategory — strategy SHOULD apply
        PricingRequest req = PricingRequest.builder()
                .factoryId(FACTORY_ID)
                .productId("P_ICE")
                .quantity(new BigDecimal("10"))
                .unitPriceList(new BigDecimal("100.00"))
                .productCategory("frozen")
                .build();

        PricingResult result = engine.simulate(req);

        // 10% off 1000 = 100 discount
        assertEquals(new BigDecimal("900.00"), result.getFinalPrice());
        assertEquals(1, result.getAppliedStrategies().size());
    }

    @Test
    @DisplayName("simulate(PricingRequest) — null request throws IllegalArgumentException")
    void simulateRequestOverload_nullRequest_throws() {
        assertThrows(IllegalArgumentException.class, () -> engine.simulate(null));
    }

    @Test
    @DisplayName("5-arg simulate (deprecated) — still delegates to new overload (backwards compat)")
    void simulate5ArgOverload_delegates() {
        // Empty repo, expect no-op pricing pass-through
        PricingResult result = engine.simulate(FACTORY_ID, "P-001", new BigDecimal("5"), new BigDecimal("20.00"), null);
        assertNotNull(result);
        assertEquals(new BigDecimal("100.00"), result.getOriginalPrice());
        assertEquals(new BigDecimal("100.00"), result.getFinalPrice());
        verify(logRepo, never()).save(any(PricingApplicationLog.class));
    }

    // ==================== Post-review 2026-05-19: negative finalPrice surfaces warning ====================

    @Test
    @DisplayName("Negative finalPrice — clamps to 0 AND surfaces warning (NOT silent)")
    void foolProof_negativeFinalPrice_addsClampWarning() {
        // Two strategies stacking: TIERED 60% off + PROMOTION 50% off
        // 60% off 1000 = 600 discount; running 400 (no clamp yet)
        // 50% off 400 (currentLineTotal!) ... wait no — PROMOTION uses lineTotal = qty*unitPriceList
        // So PROMOTION applies discountPct 50% on 1000 (qty*unit) = 500 discount
        // Total discount attempted: 600 + 500 = 1100; original 1000 → finalPrice = -100 → CLAMP
        Map<String, Object> tieredRules = mapOf("tiers", List.of(
                mapOf("minQty", 1, "discountPct", new BigDecimal("60"))));
        Map<String, Object> promoRules = mapOf("discountPct", new BigDecimal("50"));

        PricingStrategy tiered = buildStrategy("AGGR_T", PricingStrategyType.TIERED, tieredRules, 50);
        PricingStrategy promo = buildStrategy("AGGR_P", PricingStrategyType.PROMOTION, promoRules, 60);
        when(strategyRepo.findActiveByFactoryIdOrderByPriorityAsc(eq(FACTORY_ID), any(LocalDate.class)))
                .thenReturn(Arrays.asList(tiered, promo));

        PricingRequest req = baseRequest()
                .quantity(new BigDecimal("10"))
                .unitPriceList(new BigDecimal("100.00"))   // line 1000
                .build();

        PricingResult result = engine.calculate(req);

        // Clamped to 0
        assertEquals(new BigDecimal("0"), result.getFinalPrice().stripTrailingZeros());
        // MUST have clamp warning surfaced (post-review I8)
        assertFalse(result.getWarnings().isEmpty(), "Expected clamp warning, got empty list");
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("超过原价") || w.contains("调整为 0")),
                "Expected clamp warning content, got: " + result.getWarnings());
    }

    // ==================== Post-review 2026-05-19: null strategyType defensive ====================

    @Test
    @DisplayName("Null strategyType — skipped without NPE")
    void applyStrategy_nullStrategyType_skipped() {
        PricingStrategy bad = new PricingStrategy();
        bad.setId(UUID.randomUUID().toString());
        bad.setFactoryId(FACTORY_ID);
        bad.setStrategyCode("BAD_DATA");
        bad.setStrategyType(null);   // ← corrupt DB row
        bad.setRulesJson(mapOf("tiers", List.of(mapOf("minQty", 1, "discountPct", new BigDecimal("10")))));
        bad.setPriority(50);
        bad.setEnabled(true);
        when(strategyRepo.findActiveByFactoryIdOrderByPriorityAsc(eq(FACTORY_ID), any(LocalDate.class)))
                .thenReturn(List.of(bad));

        PricingRequest req = baseRequest()
                .quantity(new BigDecimal("10"))
                .unitPriceList(new BigDecimal("100.00"))
                .build();

        // Should not throw; strategy skipped, no discount
        PricingResult result = engine.calculate(req);
        assertEquals(new BigDecimal("1000.00"), result.getFinalPrice());
        assertEquals(0, result.getAppliedStrategies().size());
    }

    // ==================== Helpers ====================

    private PricingStrategy buildStrategy(String code, PricingStrategyType type,
                                           Map<String, Object> rules, int priority) {
        PricingStrategy s = new PricingStrategy();
        s.setId(UUID.randomUUID().toString());
        s.setFactoryId(FACTORY_ID);
        s.setStrategyCode(code);
        s.setStrategyName(code);
        s.setStrategyType(type);
        s.setRulesJson(rules);
        s.setPriority(priority);
        s.setEnabled(true);
        return s;
    }

    private PricingRequest.PricingRequestBuilder baseRequest() {
        return PricingRequest.builder()
                .factoryId(FACTORY_ID)
                .productId("P-DEFAULT");
    }

    private static Map<String, Object> mapOf(Object... kvs) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kvs.length; i += 2) {
            m.put((String) kvs[i], kvs[i + 1]);
        }
        return m;
    }
}
