package com.cretas.aims.controller;

import com.cretas.aims.controller.PricingStrategyController.StrategyRequest;
import com.cretas.aims.entity.pricing.PricingStrategy;
import com.cretas.aims.entity.pricing.PricingStrategyType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.pricing.PricingApplicationLogRepository;
import com.cretas.aims.repository.pricing.PricingStrategyRepository;
import com.cretas.aims.service.pricing.PricingEngine;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Boundary-validation verification for {@link PricingStrategyController} — QA Round 2026-05-19
 * findings B-P1 .. B-P5 (Pricing Tab, independent Playwright QA).
 *
 * <p>Each test asserts that a malformed write request is rejected with a specific Chinese
 * message + 4-in-1 UX hint (per {@code .claude/rules/fool-proof-design.md} cross-cutting rule)
 * BEFORE the request reaches {@link PricingStrategyRepository#save(Object)}. This guarantees
 * neither {@code curl} nor a misbehaving FE can persist:
 * <ul>
 *   <li><b>B-P2</b> overlapping TIERED intervals (e.g. {@code [0,100]} + {@code [50,200]})</li>
 *   <li><b>B-P3</b> negative {@code discountPct} (= 涨价, breaks engine semantics)</li>
 *   <li><b>B-P4</b> {@code discountPct > 100} (= 倒贴, finalPrice would go below 0)</li>
 *   <li><b>B-P5</b> {@code validTo < validFrom} (empty effective interval, never matches)</li>
 *   <li><b>B-P1</b> {@code validFrom} format is pinned to {@code yyyy-MM-dd} so date parse
 *       errors surface through the existing
 *       {@code HttpMessageNotReadableException} handler with the LocalDate-specific hint</li>
 * </ul>
 *
 * <p>Tests are pure {@code @ExtendWith(MockitoExtension)} — no Spring context — to keep them
 * fast and unambiguous about which code path threw. The B-P1 test inspects the DTO field's
 * {@link JsonFormat} reflectively rather than spinning up Jackson, because the round-trip
 * deserialization integration is exercised in service-level integration tests.
 */
@ExtendWith(MockitoExtension.class)
class PricingStrategyControllerTest {

    @Mock PricingStrategyRepository strategyRepo;
    @Mock PricingApplicationLogRepository logRepo;
    @Mock PricingEngine pricingEngine;
    @InjectMocks PricingStrategyController controller;

    // ===== B-P2: overlapping tier intervals must be rejected =====

    @Test
    @DisplayName("B-P2: 阶梯区间重叠 [0-100] + [50-200] → 400, 4-in-1 hint")
    void testOverlappingTiersRejected() {
        StrategyRequest req = newBaseTieredRequest();
        req.setRulesJson(tieredRulesJson(
                tier(0, 100, "10"),
                tier(50, 200, "15")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.createStrategy("F001", req),
                "重叠区间必须 reject");

        assertEquals(400, ex.getCode(), "code must be 400");
        assertTrue(ex.getMessage().contains("阶梯区间重叠"),
                "message must explain the failure mode: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("0") && ex.getMessage().contains("100"),
                "message must surface first tier's bounds for user identification: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("50") && ex.getMessage().contains("200"),
                "message must surface second tier's bounds: " + ex.getMessage());
        assertNotNull(ex.getActionHint(), "4-in-1 UX (a): actionHint required");
        assertEquals("warning", ex.getSeverity(), "4-in-1 UX (c): severity warning");
        assertNotNull(ex.getHintTarget(), "4-in-1 UX (d): hintTarget required");
        verify(strategyRepo, never()).save(any());
    }

    @Test
    @DisplayName("B-P2: 阶梯区间相邻 [0-100] + [101-200] → 通过 (non-overlap)")
    void testAdjacentTiersAccepted() {
        StrategyRequest req = newBaseTieredRequest();
        req.setRulesJson(tieredRulesJson(
                tier(0, 100, "10"),
                tier(101, 200, "15")));

        when(strategyRepo.findByFactoryIdAndStrategyCode(eq("F001"), anyString()))
                .thenReturn(Optional.empty());
        when(strategyRepo.save(any(PricingStrategy.class))).thenAnswer(inv -> inv.getArgument(0));

        // No exception thrown — relays through to save.
        controller.createStrategy("F001", req);
        verify(strategyRepo).save(any(PricingStrategy.class));
    }

    // ===== B-P3: negative discountPct must be rejected =====

    @Test
    @DisplayName("B-P3: discountPct=-5 (负数 = 涨价) → 400, 4-in-1 hint")
    void testNegativeDiscountRejected() {
        StrategyRequest req = newBaseTieredRequest();
        req.setRulesJson(tieredRulesJson(tier(0, 100, "-5")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.createStrategy("F001", req));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("折扣率不可为负"),
                "message must say discount cannot be negative: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("-5"),
                "message must include the violating value: " + ex.getMessage());
        assertNotNull(ex.getActionHint(), "4-in-1 UX (a): actionHint required");
        assertEquals("warning", ex.getSeverity());
        assertEquals("tiers[0].discountPct", ex.getHintTarget(),
                "4-in-1 UX (d): hintTarget must point at the violating field path");
        verify(strategyRepo, never()).save(any());
    }

    // ===== B-P4: discountPct > 100 must be rejected =====

    @Test
    @DisplayName("B-P4: discountPct=150 (>100% = 倒贴) → 400, 4-in-1 hint")
    void testDiscountOver100Rejected() {
        StrategyRequest req = newBaseTieredRequest();
        req.setRulesJson(tieredRulesJson(tier(0, 100, "150")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.createStrategy("F001", req));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("折扣率不可超过 100%"),
                "message must say discount cannot exceed 100%: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("150"),
                "message must include the violating value: " + ex.getMessage());
        assertNotNull(ex.getActionHint());
        assertEquals("warning", ex.getSeverity());
        assertEquals("tiers[0].discountPct", ex.getHintTarget());
        verify(strategyRepo, never()).save(any());
    }

    @Test
    @DisplayName("B-P3/4 boundary: discountPct=0 and 100 are both accepted (inclusive)")
    void testDiscountBoundaryAccepted() {
        // 0% (no discount) and 100% (free) are both legitimate edge cases —
        // 0% = "tier exists for metering but applies nothing", 100% = "fully promotional unit".
        StrategyRequest req = newBaseTieredRequest();
        req.setRulesJson(tieredRulesJson(
                tier(0, 50, "0"),
                tier(51, 100, "100")));

        when(strategyRepo.findByFactoryIdAndStrategyCode(eq("F001"), anyString()))
                .thenReturn(Optional.empty());
        when(strategyRepo.save(any(PricingStrategy.class))).thenAnswer(inv -> inv.getArgument(0));

        controller.createStrategy("F001", req);
        verify(strategyRepo).save(any(PricingStrategy.class));
    }

    // ===== B-P5: validTo < validFrom must be rejected =====

    @Test
    @DisplayName("B-P5: validTo (2026-01-01) < validFrom (2026-05-01) → 400, 4-in-1 hint")
    void testReversedDateRangeRejected() {
        StrategyRequest req = newBaseTieredRequest();
        req.setValidFrom(LocalDate.of(2026, 5, 1));
        req.setValidTo(LocalDate.of(2026, 1, 1));   // before validFrom

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.createStrategy("F001", req));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("validTo") && ex.getMessage().contains("validFrom"),
                "message must reference both ends of the interval: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("2026-05-01") && ex.getMessage().contains("2026-01-01"),
                "message must surface the actual values: " + ex.getMessage());
        assertNotNull(ex.getActionHint());
        assertEquals("warning", ex.getSeverity());
        assertEquals("validTo", ex.getHintTarget());
        verify(strategyRepo, never()).save(any());
    }

    @Test
    @DisplayName("B-P5 boundary: validFrom == validTo (single-day interval) accepted")
    void testSameDayDateRangeAccepted() {
        StrategyRequest req = newBaseTieredRequest();
        LocalDate sameDay = LocalDate.of(2026, 5, 19);
        req.setValidFrom(sameDay);
        req.setValidTo(sameDay);

        when(strategyRepo.findByFactoryIdAndStrategyCode(eq("F001"), anyString()))
                .thenReturn(Optional.empty());
        when(strategyRepo.save(any(PricingStrategy.class))).thenAnswer(inv -> inv.getArgument(0));

        controller.createStrategy("F001", req);
        verify(strategyRepo).save(any(PricingStrategy.class));
    }

    // ===== B-P1: validFrom/validTo @JsonFormat pinned to yyyy-MM-dd =====

    @Test
    @DisplayName("B-P1: validFrom + validTo annotated with @JsonFormat(pattern=\"yyyy-MM-dd\")")
    void testIsoDatetimeFormatGivesHint() throws NoSuchFieldException {
        // Why reflective: we want to assert the format contract at the DTO surface
        // independently of Jackson runtime. With this annotation, a full-ISO datetime
        // input like "2026-05-01T00:00:00" is rejected by Jackson with a
        // DateTimeParseException whose message contains "java.time.LocalDate" — that
        // matches GlobalExceptionHandler.handleHttpMessageNotReadableException at
        // line ~684 and routes to "日期格式不正确（值: ...），请重新选择日期",
        // NOT the generic "请求格式不正确" fallback that B-P1 surfaced.
        Field from = StrategyRequest.class.getDeclaredField("validFrom");
        JsonFormat fromFmt = from.getAnnotation(JsonFormat.class);
        assertNotNull(fromFmt, "validFrom must carry @JsonFormat to enforce date-only parse");
        assertEquals("yyyy-MM-dd", fromFmt.pattern(),
                "validFrom @JsonFormat pattern must be yyyy-MM-dd to reject datetime strings");

        Field to = StrategyRequest.class.getDeclaredField("validTo");
        JsonFormat toFmt = to.getAnnotation(JsonFormat.class);
        assertNotNull(toFmt, "validTo must carry @JsonFormat to enforce date-only parse");
        assertEquals("yyyy-MM-dd", toFmt.pattern(),
                "validTo @JsonFormat pattern must be yyyy-MM-dd to reject datetime strings");
    }

    // ===== Update path mirrors create — sister assertion to catch entry-point divergence
    //       (per QA prompt v2.4 Rule 16: handleCreate + handleEdit are independent code paths) =====

    @Test
    @DisplayName("Rule 16: updateStrategy applies the same B-P2 overlap validation as create")
    void testUpdateAppliesSameOverlapValidation() {
        StrategyRequest req = newBaseTieredRequest();
        req.setRulesJson(tieredRulesJson(
                tier(0, 100, "10"),
                tier(50, 200, "15")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.updateStrategy("F001", "some-id", req),
                "重叠区间在 update 路径也必须 reject (Rule 16: entry point matrix)");
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("阶梯区间重叠"));
        // Crucially: findById was NEVER called — validation fires before repo lookup.
        verify(strategyRepo, never()).findById(anyString());
        verify(strategyRepo, never()).save(any());
    }

    @Test
    @DisplayName("Rule 16: updateStrategy applies the same B-P5 date validation as create")
    void testUpdateAppliesSameDateValidation() {
        StrategyRequest req = newBaseTieredRequest();
        req.setValidFrom(LocalDate.of(2026, 5, 1));
        req.setValidTo(LocalDate.of(2026, 1, 1));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.updateStrategy("F001", "some-id", req));
        assertEquals(400, ex.getCode());
        verify(strategyRepo, never()).findById(anyString());
        verify(strategyRepo, never()).save(any());
    }

    // ===== Happy-path sanity check — clean TIERED create still works (no false positive) =====

    @Test
    @DisplayName("Happy path: clean TIERED create with non-overlapping tiers persists normally")
    void testCleanTieredCreatePersists() {
        StrategyRequest req = newBaseTieredRequest();
        req.setRulesJson(tieredRulesJson(
                tier(0, 99, "5"),
                tier(100, 499, "10"),
                tier(500, 9999, "15")));
        req.setValidFrom(LocalDate.of(2026, 1, 1));
        req.setValidTo(LocalDate.of(2026, 12, 31));

        when(strategyRepo.findByFactoryIdAndStrategyCode(eq("F001"), anyString()))
                .thenReturn(Optional.empty());
        when(strategyRepo.save(any(PricingStrategy.class))).thenAnswer(inv -> {
            PricingStrategy saved = inv.getArgument(0);
            saved.setId("generated-uuid");
            return saved;
        });

        var resp = controller.createStrategy("F001", req);
        assertNotNull(resp);
        assertTrue(resp.getSuccess());
        assertEquals(PricingStrategyType.TIERED, resp.getData().getStrategyType());
        assertEquals("F001", resp.getData().getFactoryId());
    }

    // ===== Edge case: non-TIERED type skips tier validation =====

    @Test
    @DisplayName("Non-TIERED types (MEMBER) skip tier validation even if rulesJson.tiers exists")
    void testNonTieredSkipsTierValidation() {
        // Even with malformed `tiers` array in a MEMBER rule, validation should not fire —
        // MEMBER's engine path reads `membershipTier` / `discountPct` / `tierDiscounts`,
        // not `tiers`. Validating it would be overreach.
        StrategyRequest req = newBaseTieredRequest();
        req.setStrategyType("MEMBER");
        Map<String, Object> rules = new HashMap<>();
        rules.put("membershipTier", "VIP");
        rules.put("discountPct", new BigDecimal("5"));
        // Stray `tiers` field with garbage — should NOT trip TIERED validation.
        rules.put("tiers", List.of(tier(0, 100, "-999")));
        req.setRulesJson(rules);

        when(strategyRepo.findByFactoryIdAndStrategyCode(eq("F001"), anyString()))
                .thenReturn(Optional.empty());
        when(strategyRepo.save(any(PricingStrategy.class))).thenAnswer(inv -> inv.getArgument(0));

        controller.createStrategy("F001", req);
        verify(strategyRepo).save(any(PricingStrategy.class));
    }

    // ===== Edge case: empty / missing rulesJson tolerated =====

    @Test
    @DisplayName("Empty rulesJson tolerated (engine returns ZERO discount at apply time)")
    void testEmptyRulesJsonTolerated() {
        StrategyRequest req = newBaseTieredRequest();
        req.setRulesJson(null);

        when(strategyRepo.findByFactoryIdAndStrategyCode(eq("F001"), anyString()))
                .thenReturn(Optional.empty());
        when(strategyRepo.save(any(PricingStrategy.class))).thenAnswer(inv -> inv.getArgument(0));

        controller.createStrategy("F001", req);
        verify(strategyRepo).save(any(PricingStrategy.class));
    }

    // ==================== Test helpers ====================

    private static StrategyRequest newBaseTieredRequest() {
        StrategyRequest req = new StrategyRequest();
        req.setStrategyCode("TEST_TIERED_001");
        req.setStrategyName("阶梯定价测试");
        req.setStrategyType("TIERED");
        req.setPriority(100);
        req.setEnabled(true);
        return req;
    }

    /** Build {@code rulesJson} with TIERED-shaped tiers. */
    private static Map<String, Object> tieredRulesJson(Map<String, Object>... tiers) {
        Map<String, Object> rules = new LinkedHashMap<>();
        List<Map<String, Object>> list = new ArrayList<>(tiers.length);
        for (Map<String, Object> t : tiers) list.add(t);
        rules.put("tiers", list);
        return rules;
    }

    private static Map<String, Object> tier(int minQty, int maxQty, String discountPct) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("minQty", minQty);
        t.put("maxQty", maxQty);
        t.put("discountPct", new BigDecimal(discountPct));
        return t;
    }
}
